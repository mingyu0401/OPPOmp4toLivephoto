package com.mingyu.livephoto

import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在设备/模拟器上验证真实转换链路。
 * 产物写到应用外部目录，便于 adb pull 后与 PC 参考输出做结构比对：
 *   ./gradlew :app:connectedDebugAndroidTest
 *   adb pull /sdcard/Android/data/com.mingyu.livephoto/files
 */
@RunWith(AndroidJUnit4::class)
class LivePhotoPipelineAndroidTest {

    private val assetNames = listOf("rotated.mp4", "upright.mp4")

    /** 素材是作者手机原片，不随仓库分发；缺失时跳过而不是报错 */
    private fun assumeAssetsPresent() {
        val available = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.list("").orEmpty().toSet()
        }.getOrDefault(emptySet())
        assumeTrue("androidTest/assets 缺少 $assetNames，跳过", assetNames.all { it in available })
    }

    @Test
    fun buildsLivePhotoFromDecodedFrames() {
        assumeAssetsPresent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val converter = LivePhotoConverter(ctx)
        val outDir = ctx.getExternalFilesDir(null) ?: error("无外部目录")
        for (asset in assetNames) {
            val video = File(ctx.cacheDir, asset)
            instrumentation.context.assets.open(asset).use { input -> video.outputStream().use { input.copyTo(it) } }
            val source = video.readBytes()

            val livePhoto = converter.buildLivePhoto(video)
            assertEquals(0xFF.toByte(), livePhoto[0])
            assertArrayEquals("MP4 尾部必须与源视频逐字节相同", source, livePhoto.copyOfRange(livePhoto.size - source.size, livePhoto.size))
            val eoi = livePhoto.copyOfRange(0, livePhoto.size - source.size)
            assertTrue("JPEG 部分应以 EOI 结尾", eoi[eoi.size - 2] == 0xFF.toByte() && eoi[eoi.size - 1] == 0xD9.toByte())

            File(outDir, "$asset.livephoto.jpg").writeBytes(livePhoto)
            video.delete()
        }
    }

    /** 完整用户流程：相册里的视频 URI -> 转换 -> 写入 DCIM/Camera */
    @Test
    fun convertsVideoFromGalleryIntoCameraFolder() {
        assumeAssetsPresent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val resolver = ctx.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MingYuE2ESource.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法插入测试视频")
        instrumentation.context.assets.open("rotated.mp4").use { input ->
            resolver.openOutputStream(videoUri)!!.use { output -> input.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(videoUri, values, null, null)

        val result = runBlocking {
            LivePhotoConverter(ctx).convert(videoUri, "DCIM/Camera", 50L, false, 0)
        }
        assertEquals("转换应成功: ${result.detail}", Status.OK, result.status)

        val name = result.detail.substringAfterLast('/')
        val imageUri = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { c -> if (c.moveToFirst()) MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .appendPath(c.getLong(0).toString()).build() else null }
            ?: error("相册里查不到 $name")

        val source = instrumentation.context.assets.open("rotated.mp4").readBytes()
        val bytes = resolver.openInputStream(imageUri)!!.readBytes()
        assertArrayEquals(
            "写入相册的文件尾部应与源视频一致",
            source,
            bytes.copyOfRange(bytes.size - source.size, bytes.size),
        )
        File(ctx.getExternalFilesDir(null), "e2e-$name").writeBytes(bytes)
        resolver.delete(imageUri, null, null)
        resolver.delete(videoUri, null, null)
    }

    /** 探明 MediaMetadataRetriever 对旋转元数据的处理方式，结论写入 probe.txt */
    @Test
    fun reportsFrameOrientationSource() {
        assumeAssetsPresent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val report = StringBuilder()
        for (asset in assetNames) {
            val video = File(ctx.cacheDir, asset)
            instrumentation.context.assets.open(asset).use { input -> video.outputStream().use { input.copyTo(it) } }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(video.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            val frame = retriever.getFrameAtTime(durationMs!! * 500, MediaMetadataRetriever.OPTION_CLOSEST)
            report.appendLine("$asset: rotation=$rotation durationMs=$durationMs frame=${frame?.width}x${frame?.height}")
            retriever.release()
            frame?.recycle()
            video.delete()
        }
        File(ctx.getExternalFilesDir(null), "probe.txt").writeText(report.toString())
    }

    /** 用户需求回归：转换产物的文件时间戳必须与源视频一致（默认 Downloads 路径） */
    @Test
    fun downloadsOutputCarriesSourceTimestamp() {
        assumeAssetsPresent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val resolver = ctx.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MingYuTsSource.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法插入测试视频")
        instrumentation.context.assets.open("rotated.mp4").use { input ->
            resolver.openOutputStream(videoUri)!!.use { output -> input.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(videoUri, values, null, null)

        val srcMtimeMs = resolver.query(
            videoUri,
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) * 1000 else 0L } ?: 0L
        assumeTrue("provider 未回写 date_modified，跳过", srcMtimeMs > 0L)

        val result = runBlocking {
            LivePhotoConverter(ctx).convertToOutput(videoUri, null, 50L, false, 0)
        }
        assertEquals("转换应成功: ${result.detail}", Status.OK, result.status)

        val name = result.detail.substringAfterLast('/')
        val raw = File(Environment.getExternalStorageDirectory(), "$DEFAULT_OUTPUT_DIR/$name")
        assertTrue("产物应存在于 $raw", raw.exists())
        assertEquals("产物文件时间戳应等于源视频", srcMtimeMs, raw.lastModified())

        val outRow = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
        outRow?.let { resolver.delete(it, null, null) }
        resolver.delete(videoUri, null, null)
        raw.delete()
    }
}
