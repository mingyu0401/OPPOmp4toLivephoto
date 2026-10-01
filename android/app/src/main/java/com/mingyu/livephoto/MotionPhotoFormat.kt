package com.mingyu.livephoto

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * OPPO 实况照片 (.JPG) 二进制组装，与 mp4_to_oppo_livephoto.py 逐字节一致。
 * 布局 [SOI][APP1 XMP][APP1 EXIF][APP2 MPF][APP0 JFIF][APP2 ICC][图像核心][MP4 尾部]
 */
object MotionPhotoFormat {

    val USER_COMMENT: ByteArray = "oplus_8388608\u0000".toByteArray(Charsets.US_ASCII)

    private val XMP_HEADER: ByteArray =
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    private val XMP_LINES = listOf(
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">",
        "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
        "    <rdf:Description rdf:about=\"\"",
        "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"",
        "        xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"",
        "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"",
        "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"",
        "      GCamera:MotionPhoto=\"1\"",
        "      GCamera:MotionPhotoVersion=\"1\"",
        "      GCamera:MotionPhotoPresentationTimestampUs=\"{ts}\"",
        "      OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"{ts}\"",
        "      OpCamera:MotionPhotoOwner=\"oplus\"",
        "      OpCamera:OLivePhotoVersion=\"2\"",
        "      OpCamera:MotionPhotoFeatureFlag=\"1\"",
        "      OpCamera:VideoLength=\"{vlen}\">",
        "      <Container:Directory>",
        "        <rdf:Seq>",
        "          <rdf:li rdf:parseType=\"Resource\">",
        "            <Container:Item",
        "              Item:Mime=\"image/jpeg\"",
        "              Item:Semantic=\"Primary\"/>",
        "          </rdf:li>",
        "          <rdf:li rdf:parseType=\"Resource\">",
        "            <Container:Item",
        "              Item:Mime=\"video/mp4\"",
        "              Item:Semantic=\"MotionPhoto\"",
        "              Item:Length=\"{vlen}\"/>",
        "          </rdf:li>",
        "        </rdf:Seq>",
        "      </Container:Directory>",
        "    </rdf:Description>",
        "  </rdf:RDF>",
        "</x:xmpmeta>",
    )

    private val JFIF_PAYLOAD: ByteArray =
        byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1)

    /** ICC sRGB 描述文件，取自可用的 OPPO 实况样本 (Google Inc. 2016 profile) */
    val ICC_PAYLOAD: ByteArray = Base64.getDecoder().decode(
        "SUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3Nw" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAAB" +
            "FAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChn" +
            "VFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5V" +
            "UwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UA" +
            "ABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQA" +
            "AAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAA" +
            "AAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADY=",
    )

    private const val MPF_SEG_FIXED_LEN = 74 // 段头(2) + 长度(2) + MPF 负载(70)

    private class Be {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v and 0xFF) }

        fun u16(v: Int) = apply {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun u32(v: Long) = apply {
            out.write(((v ushr 24) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt())
            out.write(((v ushr 8) and 0xFF).toInt())
            out.write((v and 0xFF).toInt())
        }

        fun raw(b: ByteArray) = apply { out.write(b, 0, b.size) }

        fun bytes() = out.toByteArray()
    }

    private fun appSeg(marker: Int, payload: ByteArray): ByteArray {
        check(payload.size + 2 <= 0xFFFF) { "段长度溢出: $marker" }
        return Be().u8(0xFF).u8(marker).u16(payload.size + 2).raw(payload).bytes()
    }

    fun buildXmpPayload(tsUs: Long, videoLen: Long): ByteArray {
        val xml = XMP_LINES.joinToString("\n")
            .replace("{ts}", tsUs.toString())
            .replace("{vlen}", videoLen.toString())
        return XMP_HEADER + xml.toByteArray(Charsets.UTF_8)
    }

    /** TIFF: MM 大端 + IFD(宽/高/ExifIFD/方向) + ExifIFD(UserComment/LightSource) */
    fun buildExifPayload(width: Int, height: Int): ByteArray {
        val tiff = Be()
        tiff.raw("MM\u0000*".toByteArray(Charsets.US_ASCII))
        tiff.u32(8)
        tiff.u16(4)
        tiff.u16(0x0100).u16(4).u32(1).u32(width.toLong())
        tiff.u16(0x0101).u16(4).u32(1).u32(height.toLong())
        tiff.u16(0x8769).u16(4).u32(1).u32(0x3E)
        tiff.u16(0x0112).u16(3).u32(1).u32(0)
        tiff.u32(0)
        tiff.u16(2)
        tiff.u16(0x9286).u16(2).u32(USER_COMMENT.size.toLong()).u32(0x5C)
        tiff.u16(0x9208).u16(4).u32(1).u32(0)
        tiff.u32(0)
        tiff.raw(USER_COMMENT)
        return "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.bytes()
    }

    /** MPEntry = {attr=0x30000, size=JPEG 部分长度, offset=0} */
    fun buildMpfPayload(jpegLen: Long): ByteArray {
        val tiff = Be()
        tiff.raw("MM\u0000*".toByteArray(Charsets.US_ASCII))
        tiff.u32(8)
        tiff.u16(3)
        tiff.u16(0xB000).u16(7).u32(4).raw("0100".toByteArray(Charsets.US_ASCII))
        tiff.u16(0xB001).u16(4).u32(1).u32(1)
        tiff.u16(0xB002).u16(7).u32(16).u32(0x32)
        tiff.u32(0)
        tiff.u32(0x00030000L).u32(jpegLen).u32(0).u16(0).u16(0)
        return "MPF\u0000".toByteArray(Charsets.US_ASCII) + tiff.bytes()
    }

    /** 去掉封面 JPEG 的 APP/COM 段，保留 DQT/SOF/DHT 以及从 SOS 到文件尾的全部数据（含 EOI） */
    fun coverCore(cover: ByteArray): ByteArray {
        if (cover.size < 2 || cover[0] != 0xFF.toByte() || cover[1] != 0xD8.toByte()) {
            error("封面不是合法 JPEG")
        }
        var pos = 2
        val kept = ByteArrayOutputStream()
        while (pos + 4 <= cover.size) {
            if (cover[pos] != 0xFF.toByte()) error("封面 JPEG 段解析失败")
            val marker = cover[pos + 1].toInt() and 0xFF
            val length = ((cover[pos + 2].toInt() and 0xFF) shl 8) or (cover[pos + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                kept.write(cover, pos, cover.size - pos)
                return kept.toByteArray()
            }
            if (marker == 0xDB || marker == 0xC0 || marker == 0xC2 || marker == 0xC4) {
                kept.write(cover, pos, 2 + length)
            }
            pos += 2 + length
        }
        error("封面 JPEG 缺少 SOS 段")
    }

    /** 从保留的 SOF 段里读出宽高 */
    fun readCoverDims(segments: ByteArray): IntArray {
        var pos = 0
        while (pos + 9 <= segments.size) {
            if (segments[pos] != 0xFF.toByte()) error("读取封面尺寸失败")
            val marker = segments[pos + 1].toInt() and 0xFF
            val length = ((segments[pos + 2].toInt() and 0xFF) shl 8) or (segments[pos + 3].toInt() and 0xFF)
            if (marker == 0xC0 || marker == 0xC2) {
                val h = ((segments[pos + 5].toInt() and 0xFF) shl 8) or (segments[pos + 6].toInt() and 0xFF)
                val w = ((segments[pos + 7].toInt() and 0xFF) shl 8) or (segments[pos + 8].toInt() and 0xFF)
                return intArrayOf(w, h)
            }
            pos += 2 + length
        }
        error("封面缺少 SOF 段")
    }

    /** 生成实况照片的 JPEG 部分（以 EOI 结尾，其后直接拼接 MP4） */
    fun buildJpegPart(cover: ByteArray, tsUs: Long, videoLen: Long): ByteArray {
        val core = coverCore(cover)
        val dims = readCoverDims(core)

        val xmpSeg = appSeg(0xE1, buildXmpPayload(tsUs, videoLen))
        val exifSeg = appSeg(0xE1, buildExifPayload(dims[0], dims[1]))
        val jfifSeg = appSeg(0xE0, JFIF_PAYLOAD)
        val iccSeg = appSeg(0xE2, ICC_PAYLOAD)

        val jpegLen = 2L + xmpSeg.size + exifSeg.size + jfifSeg.size + iccSeg.size + core.size + MPF_SEG_FIXED_LEN
        val mpfSeg = appSeg(0xE2, buildMpfPayload(jpegLen))

        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        out.write(xmpSeg)
        out.write(exifSeg)
        out.write(mpfSeg)
        out.write(jfifSeg)
        out.write(iccSeg)
        out.write(core)
        val jpegPart = out.toByteArray()
        check(jpegPart.size.toLong() == jpegLen) { "JPEG 总长计算不一致" }
        check(jpegPart[jpegPart.size - 2] == 0xFF.toByte() && jpegPart[jpegPart.size - 1] == 0xD9.toByte()) {
            "JPEG 未以 EOI 结尾"
        }
        return jpegPart
    }
}
