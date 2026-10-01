package com.mingyu.livephoto.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

const val GITHUB_URL = "https://github.com/mingyu0401"

/** 系统浏览器打开作者主页 */
fun openGitHub(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** 首次进入的欢迎弹窗：功能介绍 + 授权说明 + ColorOS 专用提示 */
@Composable
fun IntroDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("欢迎使用 MingYu实况") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "把 MP4 批量转成 OPPO / 一加相册可识别的实况照片：取视频中点帧做封面，" +
                        "再把原视频字节零间隙拼在 JPEG 之后，与电脑端 mp4_to_oppo_livephoto.py 的输出格式一致。" +
                        "结果默认写入 DCIM/Camera，原视频不改动。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "关于授权：本应用不需要任何运行时权限、不联网。" +
                        "转换页点「选择视频」或「选择文件夹」会打开系统文件选择器，" +
                        "首次选文件夹时系统会问「允许 MingYu实况 访问该文件夹中的文件吗」，请按「允许」，否则读不到视频。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "ColorOS 专用：转成实况照片后「创建时间」不会改变，一加 15 + ColorOS 17 测试通过。" +
                        "写这个原因是某人 vivo 换机到 OPPO 后 6000 张实况变成了 2 秒小视频。" +
                        "本 APP 120% 代码由 AI 编写。\n" +
                        "我的 GitHub 主页：mingyu0401",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = { TextButton(onClick = { openGitHub(context) }) { Text("打开 GitHub") } },
    )
}
