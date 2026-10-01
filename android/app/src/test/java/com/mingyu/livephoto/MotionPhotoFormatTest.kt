package com.mingyu.livephoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用 PC 脚本 mp4_to_oppo_livephoto.py 的真实输出做基准，逐字节验证 Kotlin 端的组装结果。
 * 基准生成：python mp4_to_oppo_livephoto.py inn ref --no-faststart
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
}
