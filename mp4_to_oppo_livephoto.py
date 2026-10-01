#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批量把视频转成 OPPO 相册识别的实况照片 (.JPG)。

输出格式 1:1 对齐 OPPO Live Photo（参考 makelivephoto.cn 已知可用样本）:
    [SOI][APP1 XMP: GCamera + OpCamera + Container/Item 目录]
         [APP1 EXIF: UserComment="oplus_8388608" + 拍摄时间]
         [APP2 MPF: MPEntry 指向视频][APP0 JFIF][APP2 ICC]
         [DQT/SOF/DHT/SOS/图像数据/EOI]
         [MP4 直接拼接, faststart, 与 EOI 零间隙]

依赖:
    pip install tqdm
    ffmpeg / ffprobe 在 PATH 里

用法:
    python mp4_to_oppo_livephoto.py <输入目录> <输出目录> [--workers 8] [--max-size-mb 50]
                                    [--retries 2] [--include-oversize] [--no-faststart]
                                    [--no-exif-date]
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

try:
    from tqdm import tqdm
except ImportError:
    print("缺少 tqdm，请先 `pip install tqdm`", file=sys.stderr)
    sys.exit(1)

VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".webm"}

USER_COMMENT = b"oplus_8388608\x00"  # OPPO 相册识别的 EXIF 用户注释

# ICC sRGB 描述文件，直接取自可用的 OPPO 实况样本 (Google Inc. 2016 profile)
ICC_PAYLOAD = base64.b64decode(
    "SUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3Nw"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAAB"
    "FAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChn"
    "VFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5V"
    "UwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UA"
    "ABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQA"
    "AAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAA"
    "AAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADY="
)

# XMP 模板与可用样本逐字节一致（数字为变量）。注意: 无 xpacket 包裹, 无 Container 前缀的属性
XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
XMP_TEMPLATE = (
    '<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">\n'
    '  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
    '    <rdf:Description rdf:about=""\n'
    '        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"\n'
    '        xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"\n'
    '        xmlns:Container="http://ns.google.com/photos/1.0/container/"\n'
    '        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"\n'
    '      GCamera:MotionPhoto="1"\n'
    '      GCamera:MotionPhotoVersion="1"\n'
    '      GCamera:MotionPhotoPresentationTimestampUs="{ts}"\n'
    '      OpCamera:MotionPhotoPrimaryPresentationTimestampUs="{ts}"\n'
    '      OpCamera:MotionPhotoOwner="oplus"\n'
    '      OpCamera:OLivePhotoVersion="2"\n'
    '      OpCamera:MotionPhotoFeatureFlag="1"\n'
    '      OpCamera:VideoLength="{vlen}">\n'
    '      <Container:Directory>\n'
    '        <rdf:Seq>\n'
    '          <rdf:li rdf:parseType="Resource">\n'
    '            <Container:Item\n'
    '              Item:Mime="image/jpeg"\n'
    '              Item:Semantic="Primary"/>\n'
    '          </rdf:li>\n'
    '          <rdf:li rdf:parseType="Resource">\n'
    '            <Container:Item\n'
    '              Item:Mime="video/mp4"\n'
    '              Item:Semantic="MotionPhoto"\n'
    '              Item:Length="{vlen}"/>\n'
    '          </rdf:li>\n'
    '        </rdf:Seq>\n'
    '      </Container:Directory>\n'
    '    </rdf:Description>\n'
    '  </rdf:RDF>\n'
    '</x:xmpmeta>'
)


# ---------------------------------------------------------------------------
# ffprobe / ffmpeg
# ---------------------------------------------------------------------------

def probe_duration(video: Path) -> float:
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "json", str(video),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, check=True)
    data = json.loads(r.stdout or "{}")
    dur = data.get("format", {}).get("duration")
    if dur is None:
        raise RuntimeError(f"ffprobe 未返回时长: {video}")
    return float(dur)


def probe_creation_time(video: Path) -> Optional[datetime]:
    """MP4 容器里的拍摄时间（mvhd creation_time）。

    相机写的是本地墙上时间，ffprobe 却给裸值加了个 Z，所以这里直接丢掉时区标记，
    否则会二次偏移；这样与手机端读 MediaStore DATE_TAKEN 的结果一致。
    """
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format_tags=creation_time",
        "-of", "json", str(video),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, check=True)
    raw = json.loads(r.stdout or "{}").get("format", {}).get("tags", {}).get("creation_time")
    if not raw:
        return None
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "").replace("+00:00", ""))
    except ValueError:
        return None
    return dt if dt.year >= 1970 else None


def extract_middle_frame(video: Path, out_jpg: Path, duration: float) -> int:
    """抽取中间帧，返回 presentation timestamp (微秒)。"""
    ts = max(0.0, duration / 2.0)
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-ss", f"{ts:.3f}",
        "-i", str(video),
        "-frames:v", "1",
        "-q:v", "2",
        str(out_jpg),
    ]
    subprocess.run(cmd, capture_output=True, text=True, check=True)
    if not out_jpg.exists() or out_jpg.stat().st_size == 0:
        raise RuntimeError(f"抽帧失败: {video}")
    return int(ts * 1_000_000)


def remux_faststart(src_video: Path, dst_video: Path) -> None:
    """无损把 moov 原子移到文件头。仅重排容器，不重编码、不去音轨。"""
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(src_video),
        "-c", "copy",
        "-movflags", "+faststart",
        str(dst_video),
    ]
    subprocess.run(cmd, capture_output=True, text=True, check=True)
    if not dst_video.exists() or dst_video.stat().st_size == 0:
        raise RuntimeError(f"faststart 重排失败: {src_video}")


# ---------------------------------------------------------------------------
# JPEG 封面处理: 剥离 ffmpeg 自带的 APP 段, 只留图像核心数据
# ---------------------------------------------------------------------------

def strip_cover_segments(cover: bytes) -> bytes:
    """去掉封面 JPEG 的所有 APP/COM 段，保留 DQT/SOF/DHT/SOS 及之后的全部数据。"""
    if cover[:2] != b"\xff\xd8":
        raise RuntimeError("封面不是合法 JPEG")
    pos = 2
    kept: list[bytes] = []
    while pos + 4 <= len(cover):
        if cover[pos] != 0xFF:
            raise RuntimeError("封面 JPEG 段解析失败")
        marker = cover[pos + 1]
        if marker == 0xDA:  # SOS: 从这里到文件尾全部保留
            return b"".join(kept) + cover[pos:]
        length = int.from_bytes(cover[pos + 2:pos + 4], "big")
        if marker in (0xDB, 0xC0, 0xC2, 0xC4):  # DQT / SOF0 / SOF2 / DHT
            kept.append(cover[pos:pos + 2 + length])
        pos += 2 + length
    raise RuntimeError("封面 JPEG 缺少 SOS 段")


def read_cover_dims(segments: bytes) -> tuple[int, int]:
    """从保留的 SOF 段里读出宽高。"""
    pos = 0
    while pos + 9 <= len(segments):
        if segments[pos] != 0xFF:
            raise RuntimeError("读取封面尺寸失败")
        marker = segments[pos + 1]
        length = int.from_bytes(segments[pos + 2:pos + 4], "big")
        if marker in (0xC0, 0xC2):  # SOF0 / SOF2: payload = 精度(1) 高(2) 宽(2) ...
            h = int.from_bytes(segments[pos + 5:pos + 7], "big")
            w = int.from_bytes(segments[pos + 7:pos + 9], "big")
            return w, h
        pos += 2 + length
    raise RuntimeError("封面缺少 SOF 段")


# ---------------------------------------------------------------------------
# EXIF / MPF / XMP 构造（与可用样本逐字节一致）
# ---------------------------------------------------------------------------

def _app_seg(marker: int, payload: bytes) -> bytes:
    assert len(payload) + 2 <= 0xFFFF
    return b"\xff" + bytes([marker]) + struct.pack(">H", len(payload) + 2) + payload


def build_exif_payload(width: int, height: int, date_str: Optional[str] = None) -> bytes:
    """MM TIFF + IFD(宽/高/方向/日期/ExifIFD) + ExifIFD(DateTimeOriginal/DateTimeDigitized/LightSource/UserComment)。

    date_str 为 None 时输出与手机 App 早期版本逐字节一致的 112 字节布局；
    给定 "yyyy:MM:dd HH:mm:ss" 时相册会按该拍摄时间排序（媒体库扫描读 EXIF）。
    """
    ascii_date = (date_str + "\x00").encode("ascii") if date_str else None
    date_len = len(ascii_date) if ascii_date else 0
    ifd0_n = 5 if ascii_date else 4
    exif_n = 4 if ascii_date else 2
    exif_off = 8 + 2 + ifd0_n * 12 + 4
    data_off = exif_off + 2 + exif_n * 12 + 4

    ifd0 = struct.pack(">H", ifd0_n)
    ifd0 += struct.pack(">HHI", 0x0100, 4, 1) + struct.pack(">I", width)   # ImageWidth
    ifd0 += struct.pack(">HHI", 0x0101, 4, 1) + struct.pack(">I", height)  # ImageLength
    if ascii_date is None:
        ifd0 += struct.pack(">HHI", 0x8769, 4, 1) + struct.pack(">I", exif_off)
        ifd0 += struct.pack(">HHI", 0x0112, 3, 1) + b"\x00\x00\x00\x00"    # Orientation = 0
    else:
        ifd0 += struct.pack(">HHI", 0x0112, 3, 1) + b"\x00\x00\x00\x00"    # Orientation = 0
        ifd0 += struct.pack(">HHI", 0x0132, 2, date_len) + struct.pack(">I", data_off)
        data_off += date_len
        ifd0 += struct.pack(">HHI", 0x8769, 4, 1) + struct.pack(">I", exif_off)
    ifd0 += struct.pack(">I", 0)

    exif = struct.pack(">H", exif_n)
    if ascii_date is not None:
        exif += struct.pack(">HHI", 0x9003, 2, date_len) + struct.pack(">I", data_off)
        data_off += date_len
        exif += struct.pack(">HHI", 0x9004, 2, date_len) + struct.pack(">I", data_off)
        data_off += date_len
        exif += struct.pack(">HHI", 0x9208, 4, 1) + struct.pack(">I", 0)   # LightSource
        exif += struct.pack(">HHI", 0x9286, 2, len(USER_COMMENT)) + struct.pack(">I", data_off)
    else:
        exif += struct.pack(">HHI", 0x9286, 2, len(USER_COMMENT)) + struct.pack(">I", data_off)
        exif += struct.pack(">HHI", 0x9208, 4, 1) + struct.pack(">I", 0)    # LightSource
    exif += struct.pack(">I", 0)

    tiff = b"MM\x00\x2a\x00\x00\x00\x08" + ifd0 + exif
    if ascii_date is not None:
        tiff += ascii_date * 3
    tiff += USER_COMMENT
    return b"Exif\x00\x00" + tiff


def build_mpf_payload(jpeg_len: int) -> bytes:
    """70 字节 MPF 负载: MPEntry = {attr=0x30000, size=JPEG 部分长度, offset=0}。"""
    tiff = bytearray()
    tiff += b"MM\x00\x2a\x00\x00\x00\x08"
    tiff += struct.pack(">H", 3)
    tiff += struct.pack(">HHI", 0xB000, 7, 4) + b"0100"                    # MPFVersion
    tiff += struct.pack(">HHI", 0xB001, 4, 1) + struct.pack(">I", 1)       # NumberOfImages
    tiff += struct.pack(">HHI", 0xB002, 7, 16) + struct.pack(">I", 0x32)   # MPEntry 偏移
    tiff += struct.pack(">I", 0)
    tiff += struct.pack(">IIIHH", 0x00030000, jpeg_len, 0, 0, 0)           # MPEntry
    return b"MPF\x00" + bytes(tiff)


def build_xmp_payload(ts_us: int, video_len: int) -> bytes:
    return XMP_HEADER + XMP_TEMPLATE.format(ts=ts_us, vlen=video_len).encode("utf-8")


# ---------------------------------------------------------------------------
# 组装
# ---------------------------------------------------------------------------

JFIF_PAYLOAD = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01"
MPF_SEG_FIXED_LEN = 2 + 2 + 70  # 段头(2) + 长度(2) + MPF 负载(70)


def build_motion_photo(cover_jpg: Path, video: Path, ts_us: int, out_path: Path,
                       date_str: Optional[str] = None) -> None:
    cover_bytes = cover_jpg.read_bytes()
    core = strip_cover_segments(cover_bytes)
    width, height = read_cover_dims(core)
    video_len = video.stat().st_size

    xmp_seg = _app_seg(0xE1, build_xmp_payload(ts_us, video_len))
    exif_seg = _app_seg(0xE1, build_exif_payload(width, height, date_str))
    jfif_seg = _app_seg(0xE0, JFIF_PAYLOAD)
    icc_seg = _app_seg(0xE2, ICC_PAYLOAD)

    # JPEG 部分总长 = SOI + 各段 + 核心数据 + MPF 段(长度固定, 内容依赖该总长)
    jpeg_len = 2 + len(xmp_seg) + len(exif_seg) + len(jfif_seg) + len(icc_seg) \
        + len(core) + MPF_SEG_FIXED_LEN
    mpf_seg = _app_seg(0xE2, build_mpf_payload(jpeg_len))

    jpeg_part = b"\xff\xd8" + xmp_seg + exif_seg + mpf_seg + jfif_seg + icc_seg + core
    assert len(jpeg_part) == jpeg_len, "JPEG 总长计算不一致"
    assert jpeg_part.rfind(b"\xff\xd9") == len(jpeg_part) - 2, "JPEG 未以 EOI 结尾"

    with open(out_path, "wb") as f:
        f.write(jpeg_part)
        f.write(video.read_bytes())


# ---------------------------------------------------------------------------
# Windows 创建时间
# ---------------------------------------------------------------------------

_EPOCH_1601 = datetime(1601, 1, 1, tzinfo=timezone.utc)


def _dt_to_filetime(dt: datetime) -> int:
    if dt.tzinfo is None:
        dt = dt.astimezone()
    delta = dt.astimezone(timezone.utc) - _EPOCH_1601
    return int(delta.total_seconds() * 10_000_000)


class _FILETIME(ctypes.Structure):
    _fields_ = [("dwLowDateTime", ctypes.c_uint32), ("dwHighDateTime", ctypes.c_uint32)]


def set_creation_time(path: Path, dt: datetime) -> None:
    """仅 Windows 生效；非 Windows 静默跳过。"""
    if os.name != "nt":
        return
    ft_val = _dt_to_filetime(dt)
    ft = _FILETIME(ft_val & 0xFFFFFFFF, (ft_val >> 32) & 0xFFFFFFFF)
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.CreateFileW(
        str(path), 0x0100, 0x7, None, 3, 0x80, None,  # FILE_WRITE_ATTRIBUTES ...
    )
    if handle == -1 or handle == 0xFFFFFFFFFFFFFFFF:
        raise OSError(f"CreateFileW 失败: {path} (err={kernel32.GetLastError()})")
    try:
        if not kernel32.SetFileTime(handle, ctypes.byref(ft), None, None):
            raise OSError(f"SetFileTime 失败: {path} (err={kernel32.GetLastError()})")
    finally:
        kernel32.CloseHandle(handle)


def get_creation_time(path: Path) -> datetime:
    return datetime.fromtimestamp(os.path.getctime(path))


# ---------------------------------------------------------------------------
# 单文件流水线
# ---------------------------------------------------------------------------

def process_one(
    src: Path,
    out_dir: Path,
    max_size_mb: float,
    include_oversize: bool,
    retries: int,
    faststart: bool = True,
    exif_date: bool = True,
) -> tuple[str, Path, Optional[str]]:
    """返回 (status, src, message)；status ∈ {"ok", "skip_oversize", "fail"}"""
    size_mb = src.stat().st_size / (1024 * 1024)
    if size_mb > max_size_mb and not include_oversize:
        return ("skip_oversize", src, f"{size_mb:.2f} MB > {max_size_mb} MB")

    dst = out_dir / (src.stem + ".jpg")
    last_err: Optional[str] = None
    for attempt in range(retries + 1):
        tmpdir = Path(tempfile.mkdtemp(prefix="mp2mp_"))
        try:
            cover = tmpdir / "cover.jpg"
            duration = probe_duration(src)
            ts_us = extract_middle_frame(src, cover, duration)

            if faststart:
                video_for_embed = tmpdir / "video.mp4"
                remux_faststart(src, video_for_embed)
            else:
                video_for_embed = src

            created = get_creation_time(src)
            modified = datetime.fromtimestamp(src.stat().st_mtime)
            shot = probe_creation_time(src) or modified
            date_str = shot.strftime("%Y:%m:%d %H:%M:%S") if exif_date else None
            build_motion_photo(cover, video_for_embed, ts_us, dst, date_str)

            try:
                set_creation_time(dst, created)
            except OSError as e:
                last_err = f"设置创建时间失败但已生成: {e}"
            os.utime(dst, (src.stat().st_atime, modified.timestamp()))
            return ("ok", src, last_err)
        except Exception as e:  # noqa: BLE001
            last_err = f"{type(e).__name__}: {e}"
            if dst.exists():
                try:
                    dst.unlink()
                except OSError:
                    pass
            if attempt < retries:
                time.sleep(0.5 * (attempt + 1))
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
    return ("fail", src, last_err or "unknown error")


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def iter_videos(root: Path):
    if root.is_file():
        if root.suffix.lower() in VIDEO_EXTS:
            yield root
        return
    for p in root.rglob("*"):
        if p.is_file() and p.suffix.lower() in VIDEO_EXTS:
            yield p


def main() -> int:
    ap = argparse.ArgumentParser(description="批量转 OPPO 实况照片 (.JPG)")
    ap.add_argument("input_dir", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--workers", type=int, default=max(2, (os.cpu_count() or 4)))
    ap.add_argument("--max-size-mb", type=float, default=50.0,
                    help="超过该大小的视频默认跳过，默认 50MB")
    ap.add_argument("--include-oversize", action="store_true",
                    help="即使超过 --max-size-mb 也强制处理")
    ap.add_argument("--retries", type=int, default=2, help="单文件失败重试次数")
    ap.add_argument("--no-faststart", action="store_true",
                    help="不对 MP4 做 faststart 无损重排（默认会重排）")
    ap.add_argument("--no-exif-date", action="store_true",
                    help="不往封面 EXIF 写拍摄时间（用于与历史逐字节基准比对）")
    args = ap.parse_args()

    if not args.input_dir.exists():
        print(f"输入目录不存在: {args.input_dir}", file=sys.stderr)
        return 2
    args.output_dir.mkdir(parents=True, exist_ok=True)

    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            print(f"PATH 里找不到 {tool}", file=sys.stderr)
            return 2

    videos = list(iter_videos(args.input_dir))
    if not videos:
        print("没有找到任何视频文件", file=sys.stderr)
        return 0

    print(f"共 {len(videos)} 个视频，输出到 {args.output_dir}，并发 {args.workers}")

    ok, skipped, failed = 0, 0, 0
    fail_log = args.output_dir / "_failed.log"
    skip_log = args.output_dir / "_skipped.log"

    with open(fail_log, "w", encoding="utf-8") as ff, \
         open(skip_log, "w", encoding="utf-8") as sf, \
         ThreadPoolExecutor(max_workers=args.workers) as ex:
        futures = {
            ex.submit(process_one, v, args.output_dir,
                      args.max_size_mb, args.include_oversize, args.retries,
                      not args.no_faststart, not args.no_exif_date): v
            for v in videos
        }
        bar = tqdm(total=len(futures), unit="file", ncols=90)
        for fut in as_completed(futures):
            src = futures[fut]
            try:
                status, path, msg = fut.result()
            except Exception as e:  # noqa: BLE001
                status, path, msg = "fail", src, f"unexpected: {e}"
            if status == "ok":
                ok += 1
            elif status == "skip_oversize":
                skipped += 1
                sf.write(f"{path}\t{msg}\n")
            else:
                failed += 1
                ff.write(f"{path}\t{msg}\n")
            bar.set_postfix(ok=ok, skip=skipped, fail=failed, refresh=False)
            bar.update(1)
        bar.close()

    print(f"\n完成: 成功 {ok}, 跳过(>阈值) {skipped}, 失败 {failed}")
    if skipped:
        print(f"  跳过列表: {skip_log}")
    if failed:
        print(f"  失败列表: {fail_log}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
