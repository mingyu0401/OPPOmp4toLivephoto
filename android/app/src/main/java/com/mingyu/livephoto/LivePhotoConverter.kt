package com.mingyu.livephoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_QUALITY = 92

enum class Status { OK, SKIPPED_OVERSIZE, FAILED }

data class ConversionResult(
    val name: String,
    val status: Status,
    val detail: String = "",
)

/** R8 会混淆异常类名，面向用户只保留 message */
fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

/** 把一个视频转成 OPPO 相册可识别的实况照片，并写入 MediaStore 图片库 */
class LivePhotoConverter(private val context: Context) {

    suspend fun convert(
        uri: Uri,
        relativePath: String,
        maxMB: Long,
        includeOversize: Boolean,
        retries: Int,
    ): ConversionResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val stem = name.substringBeforeLast('.')
        val declared = declaredSize(uri)
        if (declared != null && isOversize(declared, maxMB) && !includeOversize) {
            return@withContext ConversionResult(name, Status.SKIPPED_OVERSIZE, oversizeText(declared, maxMB))
        }

        val temp = File.createTempFile("lpsrc_", ".mp4", context.cacheDir)
        var lastErr = ""
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开视频输入流" }
                temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            val size = temp.length()
            if (isOversize(size, maxMB) && !includeOversize) {
                return@withContext ConversionResult(name, Status.SKIPPED_OVERSIZE, oversizeText(size, maxMB))
            }
            for (attempt in 0..retries) {
                try {
                    val livePhoto = buildLivePhoto(temp)
                    val target = writeToGallery(livePhoto, relativePath, "$stem.jpg")
                    val note = if (isOversize(size, maxMB)) "（超过阈值，已按设置包含）" else ""
                    return@withContext ConversionResult(name, Status.OK, "$target$note")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = errorText(e)
                    if (attempt < retries) Thread.sleep(500L * (attempt + 1))
                }
            }
            ConversionResult(name, Status.FAILED, lastErr.ifEmpty { "unknown error" })
        } finally {
            temp.delete()
        }
    }

    /** 完整流水线：抽中点帧做封面 -> 组装 JPEG 段 -> 零间隙拼接原始 MP4 字节 */
    fun buildLivePhoto(video: File): ByteArray {
        val (cover, tsUs) = extractMiddleFrame(video)
        val jpegPart = MotionPhotoFormat.buildJpegPart(cover, tsUs, video.length())
        return jpegPart + video.readBytes()
    }

    private fun isOversize(size: Long, maxMB: Long) = size > maxMB * 1024 * 1024

    private fun oversizeText(size: Long, maxMB: Long) = "%.2f MB > %d MB".format(size / 1048576.0, maxMB)

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }

    private fun declaredSize(uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val v = c.getLong(0)
                if (v > 0) return v
            }
        }
        return null
    }

    /** 返回 (封面 JPEG, presentation timestamp 微秒)，时间戳取视频中点 */
    private fun extractMiddleFrame(video: File): Pair<ByteArray, Long> {
        val retriever = MediaMetadataRetriever()
        val (frame, tsUs) = try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("读不到视频时长")
            val ts = durationMs * 500
            // 实测：getFrameAtTime 已按 display matrix 转正（1440x1080 + rotation=90 -> 1080x1440），与 ffmpeg 行为一致
            val decoded = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw IOException("抽帧失败")
            decoded to ts
        } finally {
            runCatching { retriever.release() }
        }
        val out = ByteArrayOutputStream()
        val compressed = frame.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
        frame.recycle()
        if (!compressed || out.size() == 0) throw IOException("封面编码失败")
        return out.toByteArray() to tsUs
    }

    private fun writeToGallery(livePhoto: ByteArray, relativePath: String, fileName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore 插入失败")
        resolver.openOutputStream(itemUri)?.use { out ->
            out.write(livePhoto)
            out.flush()
        } ?: throw IOException("无法打开输出流")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        val actual = resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else fileName } ?: fileName
        return "$relativePath/$actual"
    }
}
