package com.mingyu.livephoto.model

/** 深色模式档位 */
enum class ThemeMode {
    LIGHT, DARK, FOLLOW_SYSTEM;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: FOLLOW_SYSTEM
    }
}

/** 主题色（按钮/开关/选中项等交互强调色） */
enum class ThemeColor {
    GREEN, PURPLE, BLUE, ORANGE, PINK;

    companion object {
        fun fromName(name: String?): ThemeColor =
            entries.firstOrNull { it.name == name } ?: GREEN
    }
}
