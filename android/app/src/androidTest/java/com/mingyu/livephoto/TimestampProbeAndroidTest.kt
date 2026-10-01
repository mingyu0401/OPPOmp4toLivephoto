package com.mingyu.livephoto

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 探针：MediaStore.Downloads 在目标 API 上的行为（列名 / IS_PENDING / 时间戳）。
 * 结论写入 app 外部目录 probe_timestamp.txt，测试本身不失败。
 */
@RunWith(AndroidJUnit4::class)
class TimestampProbeAndroidTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private val srcEpochMs = 1673778600000L // 2023-01-15

    private fun tryInsert(label: String, make: () -> ContentValues): Uri? {
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, make())
        } catch (e: Exception) {
            report.appendLine("$label -> THROW ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        report.appendLine("$label -> ${uri ?: "NULL"}")
        return uri
    }

    private fun del(uri: Uri?) {
        if (uri != null) runCatching { resolver.delete(uri, null, null) }
    }

    private val legacyDisplayName = "_display_name"

    private val report = StringBuilder()

    @Test
    fun probesDownloadsBehavior() {
        // 1. 生产当前组合：display_name + is_pending=1
        val v1 = tryInsert("v1(display_name+pending)") { ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "probe_v1.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } }
        // 2. _display_name，无 pending
        val v2 = tryInsert("v2(_display_name)") { ContentValues().apply {
            put(legacyDisplayName, "probe_v2.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_DIR)
        } }
        // 3. _display_name + is_pending=1
        val v3 = tryInsert("v3(_display_name+pending)") { ContentValues().apply {
            put(legacyDisplayName, "probe_v3.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } }

        val candidates = listOf("v1" to v1, "v2" to v2, "v3" to v3)
        for ((label, uri) in candidates) {
            if (uri == null) continue
            try {
                resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
                val cols = resolver.query(
                    uri,
                    arrayOf(
                        legacyDisplayName,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.IS_PENDING,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        (0 until c.columnCount).joinToString(", ") { i -> "${c.getColumnName(i)}=${c.getString(i) ?: "NULL"}" }
                    } else "no row"
                } ?: "query null"
                report.appendLine("$label after write: $cols")
            } catch (e: Exception) {
                report.appendLine("$label write -> THROW ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 4. 对 v2 测试：更新 date_modified 是否生效
        if (v2 != null) {
            val upd = ContentValues().apply { put(MediaStore.MediaColumns.DATE_MODIFIED, srcEpochMs / 1000) }
            val n = runCatching { resolver.update(v2, upd, null, null) }.getOrElse { -1 }
            val stored = resolver.query(v2, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else "no row" }
            report.appendLine("v2 update date_modified rows=$n stored=$stored")
        }

        // 5. 原始路径 setLastModified 是否可行（v2 文件）
        val rawPath = File(Environment.getExternalStorageDirectory(), "$DEFAULT_OUTPUT_DIR/probe_v2.jpg")
        report.appendLine("v2 raw exists=${rawPath.exists()} readable=${rawPath.canRead()} writable=${rawPath.canWrite()}")
        val touchOk = runCatching { rawPath.setLastModified(srcEpochMs) }.getOrDefault(false)
        val rawMtime = runCatching { rawPath.lastModified() }.getOrDefault(-1L)
        report.appendLine("v2 raw setLastModified -> $touchOk, lastModified=$rawMtime (src=$srcEpochMs)")
        if (touchOk && v2 != null) {
            // raw 修改后 MediaStore 是否会把 date_modified 覆盖回去（重新扫描）
            Thread.sleep(1500)
            val stored2 = resolver.query(v2, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else "no row" }
            report.appendLine("v2 after raw touch, DB date_modified=$stored2")
        }

        candidates.forEach { (_, u) -> del(u) }
        runCatching { rawPath.delete() }
        File(ctx.getExternalFilesDir(null), "probe_timestamp.txt").writeText(report.toString())
        println("PROBE_REPORT_BEGIN\n${report}PROBE_REPORT_END")
    }
}
