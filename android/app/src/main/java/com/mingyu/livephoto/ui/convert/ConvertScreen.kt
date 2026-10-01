package com.mingyu.livephoto.ui.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingyu.livephoto.ConversionResult
import com.mingyu.livephoto.Status
import com.mingyu.livephoto.ui.settings.SettingsViewModel

/** 转换页：选择视频 -> 按设置批量转换 -> 逐条结果显示 */
@Composable
fun ConvertScreen(convertViewModel: ConvertViewModel, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val outputDirLabel by settingsViewModel.outputDirLabel.collectAsStateWithLifecycle()
    val maxMB by settingsViewModel.maxMB.collectAsStateWithLifecycle()
    val includeOversize by settingsViewModel.includeOversize.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris != null) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            convertViewModel.pick(uris.toList())
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            convertViewModel.importFolder(treeUri)
        }
    }

    val running = convertViewModel.running
    val scanning = convertViewModel.scanning
    val total = convertViewModel.sources.size

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("视频转实况照片", style = MaterialTheme.typography.headlineSmall)
            Text(
                "可用系统选择器挑单个视频，或直接选一个文件夹（含子文件夹里的 MP4/MOV 全部导入）。" +
                    "选中的视频会转成 OPPO 相册可识别的实况照片（.JPG，封面 + 内嵌 MP4），" +
                    "写入导出目录，不覆盖原视频。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !running && !scanning) {
                        Text("选择视频")
                    }
                    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !running && !scanning) {
                        Text("选择文件夹")
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            scanning -> "正在扫描文件夹…"
                            total == 0 -> "尚未选择"
                            else -> "已选 $total 个"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (total > 0 && !running && !scanning) {
                        TextButton(onClick = { convertViewModel.clear() }) { Text("清空") }
                    }
                }
                if (total > 0) {
                    val names = convertViewModel.sources.take(3).joinToString("\n") { it.fileName(context) }
                    Text(
                        if (total > 3) "$names\n…" else names,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "输出：$outputDirLabel · " + if (includeOversize) "包含超大视频" else "超过 $maxMB MB 跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = { convertViewModel.start() },
                    enabled = !running && total > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(if (running) "转换中 ${convertViewModel.done}/$total" else "开始转换")
                }
                if (running) {
                    OutlinedButton(
                        onClick = { convertViewModel.cancel() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text("取消（不再开始新的）")
                    }
                }
                if (running && total > 0) {
                    LinearProgressIndicator(
                        progress = { convertViewModel.done.toFloat() / total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }

            if (convertViewModel.results.isNotEmpty()) {
                val ok = convertViewModel.results.count { it.status == Status.OK }
                val skipped = convertViewModel.results.count { it.status == Status.SKIPPED_OVERSIZE }
                val failed = convertViewModel.results.count { it.status == Status.FAILED }
                Text("结果：成功 $ok · 跳过 $skipped · 失败 $failed", style = MaterialTheme.typography.titleSmall)
                SectionCard {
                    // 批量导入时结果可能上千条，列表只展开前 60 条
                    convertViewModel.results.take(60).forEach { ResultRow(it) }
                    val rest = convertViewModel.results.size - 60
                    if (rest > 0) {
                        Text(
                            "其余 $rest 条已省略",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "完成后可使用 MT 管理器自行转移至 DCIM/Camera，相册即可识别为实况。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun ResultRow(result: ConversionResult) {
    val icon = when (result.status) {
        Status.OK -> Icons.Filled.CheckCircle
        Status.SKIPPED_OVERSIZE -> Icons.Filled.SkipNext
        Status.FAILED -> Icons.Filled.ErrorOutline
    }
    val tint = when (result.status) {
        Status.OK -> MaterialTheme.colorScheme.primary
        Status.SKIPPED_OVERSIZE -> MaterialTheme.colorScheme.tertiary
        Status.FAILED -> MaterialTheme.colorScheme.error
    }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(result.name, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { Text(result.detail, style = MaterialTheme.typography.bodySmall) },
    )
}

private fun Uri.fileName(context: Context): String {
    context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
    }
    return lastPathSegment?.substringAfterLast('/') ?: "视频"
}
