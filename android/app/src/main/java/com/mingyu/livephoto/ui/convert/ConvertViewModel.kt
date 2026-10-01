package com.mingyu.livephoto.ui.convert

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mingyu.livephoto.ConversionResult
import com.mingyu.livephoto.LivePhotoConverter
import com.mingyu.livephoto.Status
import com.mingyu.livephoto.data.AppPreferences
import com.mingyu.livephoto.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 转换页状态：导入的视频、进度与逐条结果 */
class ConvertViewModel(app: Application) : AndroidViewModel(app) {
    private val converter = LivePhotoConverter(app.applicationContext)
    private val prefs = AppPreferences.get(app.applicationContext)
    private val resolver = app.applicationContext.contentResolver

    /** 4 路并发写结果，读-改-写必须串行，否则会丢条目 */
    private val resultLock = Any()
    private var batchJob: Job? = null

    var sources by mutableStateOf<List<Uri>>(emptyList()); private set
    var running by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var done by mutableIntStateOf(0); private set
    var results by mutableStateOf<List<ConversionResult>>(emptyList()); private set

    fun pick(uris: List<Uri>) = append(uris)

    /** 递归枚举文件夹（含子文件夹）里的视频并追加导入 */
    fun importFolder(treeUri: Uri) {
        if (scanning) return
        scanning = true
        viewModelScope.launch(Dispatchers.IO) {
            append(collectVideos(treeUri))
            scanning = false
        }
    }

    private fun append(uris: List<Uri>) {
        if (uris.isEmpty()) return
        synchronized(resultLock) {
            sources = (sources + uris).distinct()
            results = emptyList()
            done = 0
        }
    }

    fun clear() {
        synchronized(resultLock) {
            sources = emptyList()
            results = emptyList()
            done = 0
        }
    }

    private fun collectVideos(treeUri: Uri): List<Uri> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val videos = mutableListOf<Uri>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val mime = c.getString(1) ?: ""
                    val name = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(docId)
                    } else if (mime in VIDEO_MIMES ||
                        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
                    ) {
                        videos += DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return videos
    }

    /** 取消当前批次：不再开始新的视频，正在写入的那几个会完成 */
    fun cancel() {
        batchJob?.cancel()
    }

    fun start() {
        if (running || sources.isEmpty()) return
        running = true
        done = 0
        results = emptyList()

        val outputTree = prefs.getOutputTreeUri()?.let(Uri::parse)
        val maxMB = prefs.getMaxMB().toLong()
        val includeOversize = prefs.getIncludeOversize()
        val retries = prefs.getRetries()
        val targets = sources

        batchJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                coroutineScope {
                    val limiter = Semaphore(4)
                    targets.map { uri ->
                        async {
                            coroutineContext.ensureActive()
                            val result = limiter.withPermit {
                                try {
                                    converter.convertToOutput(uri, outputTree, maxMB, includeOversize, retries)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    ConversionResult(converter.displayName(uri), Status.FAILED, errorText(e))
                                }
                            }
                            synchronized(resultLock) {
                                results = results + result
                                done += 1
                            }
                        }
                    }.forEach { it.await() }
                }
            } finally {
                running = false
            }
        }
    }

    private companion object {
        // 只有 MP4/MOV 容器能原样拼进实况照片，文件夹扫描按此过滤
        val VIDEO_MIMES = setOf("video/mp4", "video/quicktime")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov")
    }
}
