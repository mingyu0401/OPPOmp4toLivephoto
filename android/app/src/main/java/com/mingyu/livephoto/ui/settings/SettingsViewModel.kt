package com.mingyu.livephoto.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import com.mingyu.livephoto.DEFAULT_OUTPUT_DIR
import com.mingyu.livephoto.data.AppPreferences
import com.mingyu.livephoto.model.ThemeColor
import com.mingyu.livephoto.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置页状态：全部即时写入 [AppPreferences]，转换页开始时按最新值执行 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences.get(app.applicationContext)
    private val resolver = app.applicationContext.contentResolver

    private val _themeMode = MutableStateFlow(prefs.getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _themeColor = MutableStateFlow(prefs.getThemeColor())
    val themeColor: StateFlow<ThemeColor> = _themeColor.asStateFlow()

    /** 导出目录的 SAF tree URI；null 表示用默认 Download/mingyuoutput */
    private val _outputTree = MutableStateFlow(prefs.getOutputTreeUri()?.let(Uri::parse))
    val outputTree: StateFlow<Uri?> = _outputTree.asStateFlow()

    /** 已选导出目录的显示名（如 mingyuoutput）；未选择时显示默认目录 */
    private val _outputDirLabel = MutableStateFlow(prefs.getOutputDirLabel() ?: DEFAULT_OUTPUT_DIR)
    val outputDirLabel: StateFlow<String> = _outputDirLabel.asStateFlow()

    private val _maxMB = MutableStateFlow(prefs.getMaxMB())
    val maxMB: StateFlow<Int> = _maxMB.asStateFlow()

    private val _includeOversize = MutableStateFlow(prefs.getIncludeOversize())
    val includeOversize: StateFlow<Boolean> = _includeOversize.asStateFlow()

    private val _retries = MutableStateFlow(prefs.getRetries())
    val retries: StateFlow<Int> = _retries.asStateFlow()

    /** 首次进入未确认过欢迎说明时为 true */
    private val _showIntro = MutableStateFlow(!prefs.getIntroSeen())
    val showIntro: StateFlow<Boolean> = _showIntro.asStateFlow()

    fun dismissIntro() {
        prefs.setIntroSeen()
        _showIntro.value = false
    }

    /** 设置页可重新打开欢迎说明 */
    fun reopenIntro() {
        _showIntro.value = true
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setThemeColor(color: ThemeColor) {
        prefs.setThemeColor(color)
        _themeColor.value = color
    }

    /** 系统选择器返回的导出目录；权限与显示名一并落盘 */
    fun setOutputTree(treeUri: Uri) {
        // 实测 API 36：直接查 tree URI 会抛 UnsupportedOperationException，必须查其 document URI
        val label = runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull() ?: "已选择的目录"
        prefs.setOutputTree(treeUri.toString(), label)
        _outputTree.value = treeUri
        _outputDirLabel.value = label
    }

    fun setMaxMB(mb: Int) {
        prefs.setMaxMB(mb)
        _maxMB.value = prefs.getMaxMB()
    }

    fun setIncludeOversize(include: Boolean) {
        prefs.setIncludeOversize(include)
        _includeOversize.value = include
    }

    fun setRetries(retries: Int) {
        prefs.setRetries(retries)
        _retries.value = prefs.getRetries()
    }
}
