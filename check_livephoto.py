#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验手机端产出的实况照片与 PC 脚本参考输出是否结构一致。

用法:
    python check_livephoto.py <手机端产物.jpg> <PC 参考.jpg> <源视频.mp4>

PC 参考生成：python mp4_to_oppo_livephoto.py <源视频或目录> <参考目录> --no-faststart
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"


def parse(jpeg: Path):
    data = jpeg.read_bytes()
    assert data[:2] == b"\xff\xd8", "缺少 SOI"
    segments = []
    tables = bytearray()
    pos = 2
    core_start = None
    while pos + 4 <= len(data):
        assert data[pos] == 0xFF, f"段标记缺失 @ {pos}"
        marker = data[pos + 1]
        length = int.from_bytes(data[pos + 2:pos + 4], "big")
        if marker == 0xDA:
            core_start = pos
            break
        segments.append((marker, data[pos + 4:pos + 2 + length]))
        if marker in (0xDB, 0xC0, 0xC2, 0xC4):
            tables += data[pos:pos + 2 + length]
        pos += 2 + length
    assert core_start is not None, "缺少 SOS"

    mpf = next(p for m, p in segments if m == 0xE2 and p[:4] == b"MPF\x00")
    mp_entry = int.from_bytes(mpf[4 + 0x32:4 + 0x36], "big")
    attr, size, offset = mp_entry, int.from_bytes(mpf[4 + 0x36:4 + 0x3A], "big"), int.from_bytes(mpf[4 + 0x3A:4 + 0x3E], "big")
    core = data[core_start:size]
    video = data[size:]
    return {
        "sof_source": bytes(tables) + core,
        "markers": [m for m, _ in segments],
        "segments": segments,
        "core": core,
        "jpeg_len": size,
        "mpf": (attr, size, offset),
        "video": video,
    }


def xmp_text(seg_payload: bytes) -> str:
    return seg_payload[len(XMP_HEADER):].decode("utf-8")


def xmp_segment(info) -> str:
    return xmp_text(next(p for m, p in info["segments"] if m == 0xE1 and p.startswith(XMP_HEADER)))


def exif_segment(info) -> bytes:
    return next(p for m, p in info["segments"] if m == 0xE1 and p.startswith(b"Exif\x00\x00"))


def icc_segment(info) -> bytes:
    return next(p for m, p in info["segments"] if m == 0xE2 and not p.startswith(b"MPF\x00"))


def dims_from_sof(core: bytes):
    pos = 0
    while pos + 9 <= len(core):
        marker = core[pos + 1]
        length = int.from_bytes(core[pos + 2:pos + 4], "big")
        if marker in (0xC0, 0xC2):
            h = int.from_bytes(core[pos + 5:pos + 7], "big")
            w = int.from_bytes(core[pos + 7:pos + 9], "big")
            return w, h
        pos += 2 + length
    raise AssertionError("core 里没有 SOF 段")


def read_ifd(tiff: bytes, start: int):
    n = int.from_bytes(tiff[start:start + 2], "big")
    entries = {}
    for i in range(n):
        at = start + 2 + i * 12
        tag = int.from_bytes(tiff[at:at + 2], "big")
        typ = int.from_bytes(tiff[at + 2:at + 4], "big")
        count = int.from_bytes(tiff[at + 4:at + 8], "big")
        entries[tag] = (typ, count, int.from_bytes(tiff[at + 8:at + 12], "big"))
    return entries


def date_fields(payload: bytes):
    """返回 EXIF 里所有日期条目的 [(tag, 值, TIFF 内偏移, 长度)]"""
    tiff = payload[6:]
    ifd0 = read_ifd(tiff, int.from_bytes(tiff[4:8], "big"))
    exif_off = ifd0.get(0x8769, (0, 0, 0))[2]
    exif = read_ifd(tiff, exif_off) if exif_off else {}
    found = []
    for entries, tags in ((ifd0, (0x0132,)), (exif, (0x9003, 0x9004))):
        for tag in tags:
            if tag in entries:
                _, count, off = entries[tag]
                value = tiff[off:off + count].split(b"\x00")[0].decode("ascii", "replace")
                found.append((tag, value, off, count))
    return found


def strip_dates(payload: bytes) -> bytes:
    """把日期条目的值区清成 0，便于比对除时间以外的 EXIF 结构"""
    out = bytearray(payload)
    for _, _, off, count in date_fields(payload):
        out[6 + off:6 + off + count] = b"\x00" * count
    return bytes(out)


def decodes(path: Path, kind: str) -> None:
    target = path
    if kind == "video":
        target = path.with_suffix(path.suffix + ".mp4")
        target.write_bytes(path.read_bytes()[parse(path)["jpeg_len"]:])
    r = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(target), "-f", "null", "-"],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise AssertionError(f"{kind} 解码失败: {r.stderr.strip()[:300]}")


def main() -> int:
    app_out, ref, source = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    a, b = parse(app_out), parse(ref)
    problems: list[str] = []

    if a["markers"][:5] != b["markers"][:5]:
        problems.append(f"段顺序不同: {a['markers'][:5]} vs {b['markers'][:5]}")

    ax, bx = xmp_segment(a), xmp_segment(b)
    pat = re.compile(r'(\w*PresentationTimestampUs|VideoLength)="(\d+)"')
    ts_a = int(re.search(r'GCamera:MotionPhotoPresentationTimestampUs="(\d+)"', ax).group(1))
    ts_b = int(re.search(r'GCamera:MotionPhotoPresentationTimestampUs="(\d+)"', bx).group(1))
    if abs(ts_a - ts_b) > 1000:
        problems.append(f"中点时间戳差 {abs(ts_a - ts_b)} us (>1000)")
    if pat.sub(r'\1="0"', ax) != pat.sub(r'\1="0"', bx):
        problems.append("XMP 模板与参考不一致")

    if len(a["video"]) != int(re.search(r'OpCamera:VideoLength="(\d+)"', ax).group(1)):
        problems.append("XMP VideoLength 与实际拼接的视频长度不符")
    if a["mpf"][1] != a["jpeg_len"] or a["mpf"][0] != 0x00030000 or a["mpf"][2] != 0:
        problems.append(f"MPF MPEntry 异常: {a['mpf']}")
    if a["video"] != source.read_bytes():
        problems.append("尾部 MP4 与源视频不一致")
    if b["video"] != source.read_bytes():
        problems.append("参考文件尾部 MP4 与源视频不一致（参考需用 --no-faststart 生成）")

    da, db = dims_from_sof(a["sof_source"]), dims_from_sof(b["sof_source"])
    if da != db:
        problems.append(f"封面尺寸不同: app {da} vs pc {db}（旋转处理有差异）")
    ea, eb = exif_segment(a), exif_segment(b)
    dates_a = {t: v for t, v, _, _ in date_fields(ea)}
    dates_b = {t: v for t, v, _, _ in date_fields(eb)}
    if strip_dates(ea) != strip_dates(eb):
        problems.append(f"EXIF 结构（除日期外）与参考不同: app {ea.hex()[:64]} / pc {eb.hex()[:64]}")
    if not dates_a.get(0x9003):
        problems.append("手机端 EXIF 缺少 DateTimeOriginal，相册会按导入时间排序")
    if icc_segment(a) != icc_segment(b):
        problems.append("ICC 段与参考不同")

    decodes(app_out, "image")
    decodes(app_out, "video")

    print(f"手机端: {app_out.name}  JPEG {a['jpeg_len']} B + 视频 {len(a['video'])} B, 封面 {da[0]}x{da[1]}, ts {ts_a} us")
    print(f"PC 参考: {ref.name}  JPEG {b['jpeg_len']} B + 视频 {len(b['video'])} B, 封面 {db[0]}x{db[1]}, ts {ts_b} us")
    print(f"拍摄时间 EXIF: 手机端 {dates_a.get(0x9003)} / PC {dates_b.get(0x9003)}")
    if problems:
        print("\n不一致:")
        for p in problems:
            print("  - " + p)
        return 1
    print("\n结构与参考一致，图像与视频均可解码。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
