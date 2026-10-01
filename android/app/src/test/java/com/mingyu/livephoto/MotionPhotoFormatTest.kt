package com.mingyu.livephoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用 PC 脚本 mp4_to_oppo_livephoto.py 的真实输出做基准，逐字节验证 Kotlin 端的组装结果。
 * 基准生成：python mp4_to_oppo_livephoto.py inn ref --no-faststart --no-exif-date
 * inn/ 与 ref/ 是作者的手机原片，不随仓库分发；缺失时整个比对自动跳过。
 */
class MotionPhotoFormatTest {

    // 单测工作目录是 app 模块目录，向上找到同时含 inn/ 与 ref/ 的工程根
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "inn").isDirectory && File(it, "ref").isDirectory }
    private val inn get() = File(checkNotNull(root), "inn")
    private val ref get() = File(checkNotNull(root), "ref")

    private class Parsed(
        val segments: List<Pair<Int, ByteArray>>,
        val tableSegments: ByteArray,
        val core: ByteArray,
        val jpegLen: Long,
        val video: ByteArray,
    )

    private fun parse(file: File): Parsed {
        val all = file.readBytes()
        assertEquals(0xFF.toByte(), all[0])
        assertEquals(0xD8.toByte(), all[1])
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        val tables = java.io.ByteArrayOutputStream()
        var pos = 2
        var coreStart = -1
        while (pos + 4 <= all.size) {
            assertEquals("段标记缺失 @ $pos", 0xFF.toByte(), all[pos])
            val marker = all[pos + 1].toInt() and 0xFF
            val length = ((all[pos + 2].toInt() and 0xFF) shl 8) or (all[pos + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                coreStart = pos
                break
            }
            segments += marker to all.copyOfRange(pos + 4, pos + 2 + length)
            if (marker == 0xDB || marker == 0xC0 || marker == 0xC2 || marker == 0xC4) {
                tables.write(all, pos, 2 + length)
            }
            pos += 2 + length
        }
        assertTrue("缺少 SOS 段", coreStart > 0)
        val jpegLen = jpegLenOf(segments)
        val core = all.copyOfRange(coreStart, jpegLen.toInt())
        return Parsed(segments, tables.toByteArray(), core, jpegLen, all.copyOfRange(jpegLen.toInt(), all.size))
    }

    /** MPEntry 的 size 字段（TIFF 内偏移 0x32，前缀 "MPF\0" 占 4 字节）给出 JPEG 部分总长 */
    private fun jpegLenOf(segments: List<Pair<Int, ByteArray>>): Long {
        val mpf = segments.first {
            it.first == 0xE2 && String(it.second, 0, 4, Charsets.US_ASCII) == "MPF\u0000"
        }
        val at = 4 + 54 // "MPF\0"(4) + TIFF 内 MPEntry.size 字段偏移 54
        return ((mpf.second[at].toLong() and 0xFF) shl 24) or ((mpf.second[at + 1].toLong() and 0xFF) shl 16) or
            ((mpf.second[at + 2].toLong() and 0xFF) shl 8) or (mpf.second[at + 3].toLong() and 0xFF)
    }

    private fun xmpOf(segments: List<Pair<Int, ByteArray>>): String {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII).size
        val xmp = segments.first {
            it.first == 0xE1 && String(it.second, 0, header, Charsets.US_ASCII) == "http://ns.adobe.com/xap/1.0/\u0000"
        }
        return String(xmp.second, header, xmp.second.size - header, Charsets.UTF_8)
    }

    private fun videos(): List<File> {
        val dir = root?.let { File(it, "inn") } ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "mp4" }?.toList().orEmpty()
    }

    /** 素材是作者本机文件，仓库里没有时整个逐字节比对跳过而不是报错 */
    private fun requireAssets() {
        assumeTrue("缺少 inn/ 或 ref/ 测试素材，跳过", root != null && videos().isNotEmpty())
    }

    @Test
    fun buildJpegPartIsByteIdenticalToPcReference() {
        requireAssets()
        for (video in videos()) {
            val refFile = File(ref, video.nameWithoutExtension + ".jpg")
            assertTrue("基准缺失: $refFile", refFile.exists())
            val parsed = parse(refFile)
            val ts = Regex("GCamera:MotionPhotoPresentationTimestampUs=\"(\\d+)\"")
                .find(xmpOf(parsed.segments))!!.groupValues[1].toLong()

            val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + parsed.tableSegments + parsed.core
            val rebuilt = MotionPhotoFormat.buildJpegPart(cover, ts, parsed.video.size.toLong())
            val refBytes = refFile.readBytes().copyOfRange(0, parsed.jpegLen.toInt())

            assertArrayEquals("JPEG 部分与 PC 输出不一致: ${video.name}", refBytes, rebuilt)
            assertArrayEquals("尾部视频应与源视频逐字节相同: ${video.name}", video.readBytes(), parsed.video)
        }
    }

    @Test
    fun segmentLayoutMatchesReference() {
        requireAssets()
        val video = videos().first()
        val parsed = parse(File(ref, video.nameWithoutExtension + ".jpg"))
        assertEquals(listOf(0xE1, 0xE1, 0xE2, 0xE0, 0xE2), parsed.segments.map { it.first }.take(5))

        val exif = parsed.segments[1].second
        assertEquals("Exif\u0000\u0000", String(exif, 0, 6, Charsets.US_ASCII))
        assertArrayEquals(
            "EXIF UserComment 应为 oplus_8388608",
            MotionPhotoFormat.USER_COMMENT,
            exif.copyOfRange(exif.size - MotionPhotoFormat.USER_COMMENT.size, exif.size),
        )
        val dims = MotionPhotoFormat.readCoverDims(parsed.tableSegments + parsed.core)
        assertEquals(MotionPhotoFormat.buildExifPayload(dims[0], dims[1]).size, exif.size)
        assertArrayEquals("ICC 段应与样本一致", MotionPhotoFormat.ICC_PAYLOAD, parsed.segments[4].second)
    }

    @Test
    fun videoLengthAndTimestampAreConsistent() {
        requireAssets()
        val video = videos().first()
        val refFile = File(ref, video.nameWithoutExtension + ".jpg")
        val parsed = parse(refFile)
        val xmp = xmpOf(parsed.segments)
        val vlen = Regex("OpCamera:VideoLength=\"(\\d+)\"").find(xmp)!!.groupValues[1].toLong()
        assertEquals(parsed.video.size.toLong(), vlen)
        assertEquals(refFile.length(), parsed.jpegLen + vlen)
        assertTrue(xmp.contains("OpCamera:OLivePhotoVersion=\"2\""))
        assertTrue(xmp.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(xmp.endsWith("</x:xmpmeta>"))
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) =
        (((u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()) and 0xFFFFFFFFL)

    /** 只够读我们自己写出的大端 TIFF：tag -> (type, 值或偏移) */
    private fun readIfd(tiff: ByteArray, start: Int): Map<Int, Pair<Int, Long>> {
        val n = u16(tiff, start)
        return (0 until n).associate { i ->
            val at = start + 2 + i * 12
            u16(tiff, at) to (u16(tiff, at + 2) to u32(tiff, at + 8))
        }
    }

    /** payload 前 6 字节是 "Exif\0\0"，TIFF 偏移要加回去 */
    private fun asciiAt(payload: ByteArray, offset: Long, count: Int) =
        String(payload, 6 + offset.toInt(), count, Charsets.US_ASCII).trimEnd('\u0000')

    @Test
    fun exifWithoutDateKeepsReferenceByteLayout() {
        val payload = MotionPhotoFormat.buildExifPayload(1080, 1440)
        val tiff = payload.copyOfRange(6, payload.size)
        assertEquals("MM\u0000*", String(tiff, 0, 4, Charsets.US_ASCII))
        assertEquals(112, payload.size)
        val ifd0 = readIfd(tiff, 8)
        assertEquals(4, ifd0.size)
        assertEquals(1080L, ifd0[0x0100]!!.second)
        assertEquals(1440L, ifd0[0x0101]!!.second)
        assertEquals(0x3EL, ifd0[0x8769]!!.second)
        assertEquals(
            "oplus_8388608",
            asciiAt(payload, readIfd(tiff, 0x3E)[0x9286]!!.second, MotionPhotoFormat.USER_COMMENT.size - 1),
        )
    }

    @Test
    fun exifDateEntriesPointAtSourceCaptureTime() {
        val date = "2023:01:15 18:30:00"
        val payload = MotionPhotoFormat.buildExifPayload(1080, 1440, date)
        val tiff = payload.copyOfRange(6, payload.size)

        val ifd0 = readIfd(tiff, 8)
        assertEquals(5, ifd0.size)
        assertEquals(listOf(0x0100, 0x0101, 0x0112, 0x0132, 0x8769), ifd0.keys.sorted())
        assertEquals(date, asciiAt(payload, ifd0[0x0132]!!.second, 20))

        val exif = readIfd(tiff, ifd0[0x8769]!!.second.toInt())
        assertEquals(4, exif.size)
        assertEquals(listOf(0x9003, 0x9004, 0x9208, 0x9286), exif.keys.sorted())
        assertEquals(date, asciiAt(payload, exif[0x9003]!!.second, 20))
        assertEquals(date, asciiAt(payload, exif[0x9004]!!.second, 20))
        assertArrayEquals(
            MotionPhotoFormat.USER_COMMENT,
            payload.copyOfRange(6 + exif[0x9286]!!.second.toInt(), payload.size),
        )
    }

    /** 带日期的产物结构仍要自洽：MPF 的 size 必须等于 JPEG 部分实际长度 */
    @Test
    fun datedJpegPartKeepsMpfLengthConsistent() {
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x05, 0x00, 0x01, 0x02)
        val sof = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0F, 0x08, 0x01, 0x20, 0x01, 0x2C,
            0x01, 0x01, 0x11, 0x00, 0, 0, 0, 0,
        )
        val dht = byteArrayOf(0xFF.toByte(), 0xC4.toByte(), 0x00, 0x05, 0x00, 0x01, 0x02)
        val sos = byteArrayOf(
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x7F, 0x50, 0x03, 0x40,
        )
        val fakeCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + dqt + sof + dht + sos +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val videoLen = 4096L
        val jpeg = MotionPhotoFormat.buildJpegPart(
            fakeCover, 1_500_000L, videoLen, MotionPhotoFormat.formatExifDate(1_673_778_600_000L),
        )
        val parsed = parseBytes(jpeg + ByteArray(videoLen.toInt()))
        assertEquals(jpeg.size.toLong(), parsed.jpegLen)
        assertEquals(videoLen, parsed.video.size.toLong())
        assertEquals(listOf(0xE1, 0xE1, 0xE2, 0xE0, 0xE2), parsed.segments.map { it.first }.take(5))
        val exif = parsed.segments[1].second
        val tiff = exif.copyOfRange(6, exif.size)
        val ifd0 = readIfd(tiff, 8)
        assertEquals(300L, ifd0[0x0100]!!.second)
        assertEquals(288L, ifd0[0x0101]!!.second)
        assertEquals("2023:01:15", asciiAt(exif, ifd0[0x0132]!!.second, 10))
    }

    /** PC 脚本 build_exif_payload(300, 288, "2023:01:15 18:30:00") 的输出，跨端逐字节对齐 */
    private val pyDatedExif = "4578696600004d4d002a00000008000501000004000000010000012c010100040000000100000120" +
        "01120003000000010000000001320002000000140000008087690004000000010000004a000000000004900300020000" +
        "0014000000949004000200000014000000a8920800040000000100000000928600020000000e000000bc000000003230" +
        "32333a30313a31352031383a33303a303000323032333a30313a31352031383a33303a303000323032333a30313a3135" +
        "2031383a33303a3030006f706c75735f3833383836303800"

    private val pyPlainExif = "4578696600004d4d002a00000008000401000004000000010000012c010100040000000100000120" +
        "87690004000000010000003e011200030000000100000000000000000002928600020000000e0000005c9208000400000" +
        "00100000000000000006f706c75735f3833383836303800"

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun exifPayloadsMatchPcScriptByteForByte() {
        assertArrayEquals(hexToBytes(pyDatedExif), MotionPhotoFormat.buildExifPayload(300, 288, "2023:01:15 18:30:00"))
        assertArrayEquals(hexToBytes(pyPlainExif), MotionPhotoFormat.buildExifPayload(300, 288))
    }

    /** 与 parse(File) 相同的解析，作用于内存字节 */
    private fun parseBytes(all: ByteArray): Parsed {
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        val tables = java.io.ByteArrayOutputStream()
        var pos = 2
        var coreStart = -1
        while (pos + 4 <= all.size) {
            assertEquals(0xFF.toByte(), all[pos])
            val marker = all[pos + 1].toInt() and 0xFF
            val length = ((all[pos + 2].toInt() and 0xFF) shl 8) or (all[pos + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                coreStart = pos
                break
            }
            segments += marker to all.copyOfRange(pos + 4, pos + 2 + length)
            if (marker == 0xDB || marker == 0xC0 || marker == 0xC2 || marker == 0xC4) {
                tables.write(all, pos, 2 + length)
            }
            pos += 2 + length
        }
        assertTrue(coreStart > 0)
        val jpegLen = jpegLenOf(segments)
        val core = all.copyOfRange(coreStart, jpegLen.toInt())
        return Parsed(segments, tables.toByteArray(), core, jpegLen, all.copyOfRange(jpegLen.toInt(), all.size))
    }
}
