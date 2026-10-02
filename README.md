# 明雨实况 / OPPO Live Photo

把普通 MP4 转成一加 / OPPO（ColorOS）相册能识别成「实况」的照片。vivo 换机到 OPPO 之后 6000 张实况全成了 2 秒小视频，于是有了这个工具。

两个入口，同一套字节格式：

- **Android App**（`android/`）：手机上直接选视频或整个文件夹，批量转换。
- **PC 脚本**（`mp4_to_oppo_livephoto.py`）：电脑上批量处理，输出后可以再拷回手机。

成品 APK 在 [Releases](https://github.com/mingyu0401/OPPOmp4toLivephoto/releases) 里，需要 Android 10（API 29）及以上的 ColorOS 设备。

## App 怎么用

1. 装好打开，首页选「选择视频」（系统选择器，可多选）或「选择文件夹」（会连子目录里的 MP4/MOV 一起扫）。
2. 点「开始转换」，产物默认写到 `Download/mingyuoutput`，不覆盖原视频。
3. 转换完成后用 MT 管理器把产物移动到 `DCIM/Camera`，相册里就是实况。

不需要任何运行时权限，也不联网。设置页可调：深色模式、主题色、导出目录（系统选择器挑，默认 `Download/mingyuoutput`）、体积阈值（默认 50 MB，可选择「包含超大视频」）、失败重试次数。

## PC 脚本怎么用

依赖 `ffmpeg` / `ffprobe` 在 PATH 里，以及 `pip install tqdm`。

```bash
python mp4_to_oppo_livephoto.py <输入目录或视频> <输出目录> \
    --workers 8 --max-size-mb 50 --retries 2 --include-oversize --no-faststart
```

- 默认对 MP4 做 faststart 无损重排（实况视频要能边下边播），`--no-faststart` 关闭。
- 超过阈值的视频默认跳过，跳过与失败清单分别落在输出目录的 `_skipped.log` / `_failed.log`。
- 产物会把源文件的创建时间和修改时间对齐到源视频，并往封面 EXIF 写拍摄时间。
- `--no-exif-date` 只用于复现历史逐字节基准，日常不用加。

## 产物格式

```
[SOI]
[APP1 XMP  : GCamera + OpCamera + Container/Item 目录]
[APP1 EXIF : UserComment="oplus_8388608" + DateTimeOriginal 等拍摄时间]
[APP2 MPF  : MPEntry.size = JPEG 部分总长]
[APP0 JFIF][APP2 ICC]
[DQT/SOF/DHT/SOS/图像数据/EOI]
[原始 MP4 字节，与 EOI 零间隙拼接]
```

封面取视频中点帧，并按视频旋转元数据转正；MP4 尾部保持源文件逐字节不变。App 与 PC 脚本写出的段结构一致，有单测锁死这条对齐关系。

## 开发

```bash
cd android
./gradlew :app:testDebugUnitTest        # JVM 单测，含与 PC 输出的逐字节比对
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest  # 需要连着的设备/模拟器，跑真实转换链路
./gradlew :app:assembleRelease          # 需要在 android/app/ 放 keystore.properties
```

- 单测的逐字节基准来自本机原片（`inn/` 输入、`ref/` PC 参考输出）与 `androidTest/assets/*.mp4`，这些素材不入库；缺失时相关用例自动跳过而不是报错。重新生成基准请用 `python mp4_to_oppo_livephoto.py inn ref --no-faststart --no-exif-date`。
- `check_livephoto.py` 用来比对手机产物与 PC 参考：段顺序、时间戳、MPF 长度、尾部 MP4 是否逐字节相同、封面尺寸是否一致，并额外用 ffmpeg 解一遍图和视频。

```bash
python check_livephoto.py <手机端产物.jpg> <PC 参考.jpg> <源视频.mp4>
```

## 已知限制

- **相册排序**读的是照片自身的拍摄时间，不是文件系统 mtime，所以 v2.1 起会把源视频时间写进封面 EXIF；跨品牌/跨 ROM 的表现以真机为准。
- 导出到 `Download/` 的文件不会被相册当作相机照片，需要手动移到 `DCIM/Camera`；这是 ColorOS 的识别规则，不是转换失败。
- MediaStore 的 `datetaken` / `date_modified` 列由 provider 说了算，应用写不动（`android/app/src/androidTest/.../TimestampProbeAndroidTest.kt` 是留下证据的探针），文件 mtime 则可以通过原始路径 `setLastModified` 回写。
- 只针对 OPPO / 一加的实况格式，别的品牌的「实况」另有自己的封装。

本 APP 120% 代码由 AI 编写。
