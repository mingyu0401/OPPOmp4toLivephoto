package com.mingyu.livephoto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mingyu.livephoto.model.ThemeColor
import com.mingyu.livephoto.model.ThemeMode

/** 每个主题色在浅色/深色方案下的 primary/secondary/tertiary（Material3 色调） */
private data class AccentScheme(
    val lightPrimary: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
)

private val AccentSchemes = mapOf(
    ThemeColor.GREEN to AccentScheme(
        Color(0xFF006E4F), Color(0xFF4D6357), Color(0xFF3A655F),
        Color(0xFF6FDBAC), Color(0xFFB4C9BA), Color(0xFFA3CFBE),
    ),
    ThemeColor.PURPLE to AccentScheme(
        Purple40, PurpleGrey40, Pink40,
        Purple80, PurpleGrey80, Pink80,
    ),
    ThemeColor.BLUE to AccentScheme(
        Color(0xFF00639B), Color(0xFF51606F), Color(0xFF6A5779),
        Color(0xFF9FCAFF), Color(0xFFB4C9DA), Color(0xFFD7BDE3),
    ),
    ThemeColor.ORANGE to AccentScheme(
        Color(0xFF8A5100), Color(0xFF735839), Color(0xFF6B5257),
        Color(0xFFFFB967), Color(0xFFE1C3A6), Color(0xFFDDBBC3),
    ),
    ThemeColor.PINK to AccentScheme(
        Color(0xFF9C405F), Color(0xFF74555E), Color(0xFF8E4F52),
        Color(0xFFF5B2C7), Color(0xFFE2BCC7), Color(0xFFE6B5B5),
    ),
)

private fun accentSchemeOf(color: ThemeColor): AccentScheme =
    AccentSchemes.getValue(color)

/** 主题色的代表色（设置页色板/圆圈按钮用），按深浅色取对应 primary */
fun themeColorSwatch(color: ThemeColor, dark: Boolean): Color {
    val scheme = accentSchemeOf(color)
    return if (dark) scheme.darkPrimary else scheme.lightPrimary
}

/**
 * 「MingYu实况」主题：浅色 / 深色 / 跟随系统三档 + 五种主题色，切换即时生效。
 * 两档设置由 [ThemeMode]/[ThemeColor] 持久化在 AppPreferences 中，SettingsViewModel 驱动。
 */
@Composable
fun MingYuTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    themeColor: ThemeColor = ThemeColor.GREEN,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = accentSchemeOf(themeColor)
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = scheme.darkPrimary,
            secondary = scheme.darkSecondary,
            tertiary = scheme.darkTertiary,
        )
    } else {
        lightColorScheme(
            primary = scheme.lightPrimary,
            secondary = scheme.lightSecondary,
            tertiary = scheme.lightTertiary,
        )
    }
    androidx.compose.material3.MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
