package com.mingyu.livephoto.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mingyu.livephoto.data.AppPreferences
import com.mingyu.livephoto.model.ThemeColor
import com.mingyu.livephoto.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置页状态：全部即时写入 [AppPreferences]，转换页开始时按最新值执行 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences.get(app.applicationContext)

    private val _themeMode = MutableStateFlow(prefs.getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _themeColor = MutableStateFlow(prefs.getThemeColor())
    val themeColor: StateFlow<ThemeColor> = _themeColor.asStateFlow()

    private val _relativePath = MutableStateFlow(prefs.getRelativePath())
    val relativePath: StateFlow<String> = _relativePath.asStateFlow()

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

    /** 输入过程中允许中间态，失焦/开始转换时再落盘 */
    fun setRelativePathDraft(path: String) {
        _relativePath.value = path
    }

    fun commitRelativePath() {
        prefs.setRelativePath(_relativePath.value)
        _relativePath.value = prefs.getRelativePath()
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
