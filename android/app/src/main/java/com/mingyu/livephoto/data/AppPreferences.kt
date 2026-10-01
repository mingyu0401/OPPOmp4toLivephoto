package com.mingyu.livephoto.data

import android.content.Context
import android.content.SharedPreferences
import com.mingyu.livephoto.model.ThemeColor
import com.mingyu.livephoto.model.ThemeMode

/** 全局设置（SharedPreferences），转换页与设置页共用同一份 */
class AppPreferences private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mingyu_live", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode = ThemeMode.fromName(sp.getString(KEY_THEME_MODE, null))

    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getThemeColor(): ThemeColor = ThemeColor.fromName(sp.getString(KEY_THEME_COLOR, null))

    fun setThemeColor(color: ThemeColor) {
        sp.edit().putString(KEY_THEME_COLOR, color.name).apply()
    }

    /** 实况照片写入相册的相对目录 */
    fun getRelativePath(): String = sp.getString(KEY_RELATIVE_PATH, DEFAULT_RELATIVE_PATH) ?: DEFAULT_RELATIVE_PATH

    fun setRelativePath(path: String) {
        sp.edit().putString(KEY_RELATIVE_PATH, normalizePath(path)).apply()
    }

    fun getMaxMB(): Int = sp.getInt(KEY_MAX_MB, DEFAULT_MAX_MB).coerceIn(5, 200)

    fun setMaxMB(mb: Int) {
        sp.edit().putInt(KEY_MAX_MB, mb.coerceIn(5, 200)).apply()
    }

    fun getIncludeOversize(): Boolean = sp.getBoolean(KEY_INCLUDE_OVERSIZE, false)

    fun setIncludeOversize(include: Boolean) {
        sp.edit().putBoolean(KEY_INCLUDE_OVERSIZE, include).apply()
    }

    fun getRetries(): Int = sp.getInt(KEY_RETRIES, DEFAULT_RETRIES).coerceIn(0, 5)

    fun setRetries(retries: Int) {
        sp.edit().putInt(KEY_RETRIES, retries.coerceIn(0, 5)).apply()
    }

    /** 首次进入的欢迎与授权说明是否已确认 */
    fun getIntroSeen(): Boolean = sp.getBoolean(KEY_INTRO_SEEN, false)

    fun setIntroSeen() {
        sp.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "DCIM/Camera"
        const val DEFAULT_MAX_MB = 50
        const val DEFAULT_RETRIES = 2

        /** 去掉首尾斜杠与空白，空值回退到默认相册目录 */
        fun normalizePath(raw: String): String = raw.trim().trim('/').ifEmpty { DEFAULT_RELATIVE_PATH }

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_RELATIVE_PATH = "relative_path"
        private const val KEY_MAX_MB = "max_mb"
        private const val KEY_INCLUDE_OVERSIZE = "include_oversize"
        private const val KEY_RETRIES = "retries"
        private const val KEY_INTRO_SEEN = "intro_seen"

        @Volatile
        private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
    }
}
