package com.mingyu.livephoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingyu.livephoto.ui.IntroDialog
import com.mingyu.livephoto.ui.MingYuApp
import com.mingyu.livephoto.ui.convert.ConvertViewModel
import com.mingyu.livephoto.ui.settings.SettingsViewModel
import com.mingyu.livephoto.ui.theme.MingYuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val convertViewModel: ConvertViewModel by viewModels()
        val settingsViewModel: SettingsViewModel by viewModels()

        setContent {
            AppRoot(convertViewModel = convertViewModel, settingsViewModel = settingsViewModel)
        }
    }
}

/** 主题随设置即时切换，首次进入展示欢迎与授权说明 */
@Composable
private fun AppRoot(convertViewModel: ConvertViewModel, settingsViewModel: SettingsViewModel) {
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val themeColor by settingsViewModel.themeColor.collectAsStateWithLifecycle()
    val showIntro by settingsViewModel.showIntro.collectAsStateWithLifecycle()

    MingYuTheme(themeMode = themeMode, themeColor = themeColor) {
        MingYuApp(convertViewModel = convertViewModel, settingsViewModel = settingsViewModel)
        if (showIntro) {
            IntroDialog(onDismiss = { settingsViewModel.dismissIntro() })
        }
    }
}
