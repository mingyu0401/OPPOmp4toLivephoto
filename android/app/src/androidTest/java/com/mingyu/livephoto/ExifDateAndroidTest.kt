package com.mingyu.livephoto

import android.content.ContentValues
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 实测：产物必须自带源视频的拍摄时间（封面 EXIF DateTimeOriginal），相册才有依据排序。
 * 注意 MediaStore 的 datetaken 列在这台 AOSP 模拟器上连普通 JPEG 都不填，所以这里只校验文件本体。
 */
@RunWith(AndroidJUnit4::class)
class ExifDateAndroidTest {

    private fun queryLong(uri: Uri, column: String): Long? =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            .query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }

    @Test
    fun convertedFileCarriesSourceCaptureTime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = runCatching { instrumentation.context.assets.list("").orEmpty().toSet() }
            .getOrDefault(emptySet())
        assumeTrue("androidTest/assets 缺少素材，跳过", "upright.mp4" in assets)

        val ctx = instrumentation.targetContext
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ExifDateSource.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法插入测试视频")
        instrumentation.context.assets.open("upright.mp4").use { input ->
            resolver.openOutputStream(videoUri)!!.use { output -> input.copyTo(output) }
        }
        resolver.update(videoUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

        // 等扫描器给源视频行填上拍摄时间（MP4 容器 creation_time）
        var srcTaken = 0L
        for (i in 0 until 20) {
            srcTaken = queryLong(videoUri, MediaStore.Video.Media.DATE_TAKEN) ?: 0L
            if (srcTaken > 0) break
            Thread.sleep(1000)
        }
        assertTrue("源视频行应能读到拍摄时间，实测=$srcTaken", srcTaken > 0)

        val result = runBlocking { LivePhotoConverter(ctx).convertToOutput(videoUri, null, 50L, false, 0) }
        assertEquals("转换应成功: ${result.detail}", Status.OK, result.status)
        val name = result.detail.substringAfterLast('/')

        val imageUri = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ).firstNotNullOfOrNull { collection ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name), null,
            )?.use { c -> if (c.moveToFirst()) collection.buildUpon().appendPath(c.getLong(0).toString()).build() else null }
        } ?: error("媒体库里查不到 $name")

        resolver.openInputStream(imageUri)!!.use { input ->
            val exif = ExifInterface(input)
            val expected = MotionPhotoFormat.formatExifDate(srcTaken)
            assertEquals("DateTimeOriginal 应等于源视频拍摄时间", expected, exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME))
            assertNotNull("封面尺寸仍要能被读出", exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH))
            assertTrue(
                "UserComment 仍要保住 oplus 标记: ${exif.getAttribute(ExifInterface.TAG_USER_COMMENT)}",
                exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.startsWith("oplus_8388608") == true,
            )
        }

        val bytes = resolver.openInputStream(imageUri)!!.readBytes()
        val source = instrumentation.context.assets.open("upright.mp4").readBytes()
        assertTrue(
            "MP4 尾部仍需逐字节相同",
            bytes.copyOfRange(bytes.size - source.size, bytes.size).contentEquals(source),
        )

        resolver.delete(imageUri, null, null)
        resolver.delete(videoUri, null, null)
    }
}
