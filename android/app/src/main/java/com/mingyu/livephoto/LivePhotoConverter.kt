package com.mingyu.livephoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_QUALITY = 92

/** 默认导出目录：MediaStore Downloads 只允许 Download 子树，无需任何权限 */
const val DEFAULT_OUTPUT_DIR = "Download/mingyuoutput"

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

    /** 写入 MediaStore 图片库的指定相册目录（仅 DCIM/Pictures 子树合法） */
    suspend fun convert(
        uri: Uri,
        relativePath: String,
        maxMB: Long,
        includeOversize: Boolean,
        retries: Int,
    ): ConversionResult = convertTo(uri, maxMB, includeOversize, retries) { bytes, fileName, srcMtime ->
        writeToGallery(bytes, relativePath, fileName, srcMtime)
    }

    /** 应用内转换入口：outputTree 为设置页选的 SAF 目录；null 时写入默认 Download/mingyuoutput */
    suspend fun convertToOutput(
        uri: Uri,
        outputTree: Uri?,
        maxMB: Long,
        includeOversize: Boolean,
        retries: Int,
    ): ConversionResult = convertTo(uri, maxMB, includeOversize, retries) { bytes, fileName, srcMtime ->
        if (outputTree != null) writeToTree(bytes, outputTree, fileName, srcMtime) else writeToDownloads(bytes, fileName, srcMtime)
    }

    private suspend fun convertTo(
        uri: Uri,
        maxMB: Long,
        includeOversize: Boolean,
        retries: Int,
        write: (ByteArray, String, Long) -> String,
    ): ConversionResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val stem = name.substringBeforeLast('.')
        val fileName = "$stem.jpg"
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
            val srcMtime = sourceMtimeMs(uri)
            val captureMs = sourceCaptureMs(uri) ?: srcMtime
            for (attempt in 0..retries) {
                try {
                    val livePhoto = buildLivePhoto(temp, captureMs)
                    val target = write(livePhoto, fileName, srcMtime)
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
    fun buildLivePhoto(video: File, captureMs: Long? = null): ByteArray {
        val (cover, tsUs) = extractMiddleFrame(video)
        val dateTime = captureMs?.let(MotionPhotoFormat::formatExifDate)
        val jpegPart = MotionPhotoFormat.buildJpegPart(cover, tsUs, video.length(), dateTime)
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

    /**
     * 源视频的拍摄时间（毫秒），读不到返回 null。相册排序读的是封面 EXIF 的 DateTimeOriginal，
     * 所以要把这个时间写进产物 JPEG；DATE_TAKEN 优先，其次文件 mtime。
     */
    private fun sourceCaptureMs(uri: Uri): Long? {
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val v = c.getLong(0)
                    if (v > 0) return v
                }
            }
        }
        return null
    }

    /**
     * 源视频的时间戳（毫秒）。实测 MediaProvider 会覆盖 date_modified，
     * 客户端改不动数据库列，只能拿到值后把输出文件用原始路径 setLastModified 成同样的时间。
     */
    private fun sourceMtimeMs(uri: Uri): Long {
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) * 1000
            }
        }
        return System.currentTimeMillis()
    }

    /** 实测（API 36 模拟器）：MediaStore 写入的文件可经原始路径访问，setLastModified 真实生效 */
    private fun touchRawMtime(relativePath: String, fileName: String, mtimeMs: Long) {
        runCatching {
            val rel = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            val raw = File(Environment.getExternalStorageDirectory(), rel + fileName)
            if (raw.exists()) raw.setLastModified(mtimeMs)
        }
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

    private fun writeToGallery(livePhoto: ByteArray, relativePath: String, fileName: String, srcMtimeMs: Long): String {
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
        val rel = resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else relativePath } ?: relativePath
        touchRawMtime(rel, actual, srcMtimeMs)
        return "$relativePath/$actual"
    }

    /** 默认导出：写进 Download/mingyuoutput（Downloads 集合不需要任何权限） */
    private fun writeToDownloads(livePhoto: ByteArray, fileName: String, srcMtimeMs: Long): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("写入 $DEFAULT_OUTPUT_DIR 失败")
        resolver.openOutputStream(itemUri)?.use { out ->
            out.write(livePhoto)
            out.flush()
        } ?: throw IOException("无法打开输出流")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        val actual = resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else fileName } ?: fileName
        val rel = resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else DEFAULT_OUTPUT_DIR } ?: DEFAULT_OUTPUT_DIR
        touchRawMtime(rel, actual, srcMtimeMs)
        return "$DEFAULT_OUTPUT_DIR/$actual"
    }

    /**
     * 设置页选的 SAF 目录。
     * 主存储且位于 Download/DCIM/Pictures 子树时走 MediaStore：文件归本应用，
     * 才能用原始路径回写 mtime（实测 SAF 直写的文件不属于应用，setLastModified 无效）。
     * 其余位置退回 DocumentsContract 直写（mtime 无法保证）。
     */
    private fun writeToTree(livePhoto: ByteArray, treeUri: Uri, fileName: String, srcMtimeMs: Long): String {
        val resolver = context.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        if (docId.startsWith("primary:")) {
            val rel = docId.substringAfter(':').trimEnd('/') + "/"
            val collection: Uri? = when {
                rel.startsWith("Download/") -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                rel.startsWith("DCIM/") || rel.startsWith("Pictures/") ->
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            if (collection != null) {
                val itemUri = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel.trimEnd('/'))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: throw IOException("写入所选目录失败")
                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(livePhoto)
                    out.flush()
                } ?: throw IOException("无法打开输出流")
                resolver.update(itemUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)

                val actual = resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else fileName } ?: fileName
                touchRawMtime(rel, actual, srcMtimeMs)
                val dirName = runCatching {
                    resolver.query(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                }.getOrNull() ?: rel.trimEnd('/').substringAfterLast('/')
                return "$dirName/$actual"
            }
        }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val docUri = DocumentsContract.createDocument(resolver, parentUri, "image/jpeg", fileName)
            ?: throw IOException("在所选目录创建文件失败")
        resolver.openOutputStream(docUri)?.use { out ->
            out.write(livePhoto)
            out.flush()
        } ?: throw IOException("无法打开输出流")

        val dirName = runCatching {
            resolver.query(
                parentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull() ?: "所选目录"
        val actual = resolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else fileName } ?: fileName
        return "$dirName/$actual"
    }
}
