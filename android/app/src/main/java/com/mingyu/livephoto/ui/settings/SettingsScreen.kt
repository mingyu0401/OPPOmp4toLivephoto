package com.mingyu.livephoto.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingyu.livephoto.model.ThemeColor
import com.mingyu.livephoto.model.ThemeMode
import com.mingyu.livephoto.ui.openGitHub
import com.mingyu.livephoto.ui.theme.themeColorSwatch

private enum class SubPage { APPEARANCE, THEME_COLOR }

/**
 * 设置页：深色模式三档 + 五种主题色（二级菜单，即时生效）；
 * 转换默认项（相册目录、大小阈值、是否包含超大视频、失败重试）；ColorOS 说明卡片与 GitHub 链接；关于与版本号。
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    val relativePath by viewModel.relativePath.collectAsStateWithLifecycle()
    val maxMB by viewModel.maxMB.collectAsStateWithLifecycle()
    val includeOversize by viewModel.includeOversize.collectAsStateWithLifecycle()
    val retries by viewModel.retries.collectAsStateWithLifecycle()

    var subPage by remember { mutableStateOf<SubPage?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    BackHandler(enabled = subPage != null) { subPage = null }

    val darkTheme = themeMode == ThemeMode.DARK ||
        (themeMode == ThemeMode.FOLLOW_SYSTEM && isSystemInDarkTheme())
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (subPage) {
                null -> mainList(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    themeColor = themeColor,
                    darkTheme = darkTheme,
                    relativePath = relativePath,
                    maxMB = maxMB,
                    includeOversize = includeOversize,
                    retries = retries,
                    versionName = versionName,
                    onOpenSubPage = { subPage = it },
                    onOpenAbout = { showAbout = true },
                )

                SubPage.APPEARANCE -> subPageScaffold("深色模式", onBack = { subPage = null }) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(themeModeLabel(mode)) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    Text(
                        "切换后立即生效，无需重启应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                SubPage.THEME_COLOR -> subPageScaffold("主题色", onBack = { subPage = null }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ThemeColor.entries.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(themeColorSwatch(color, darkTheme))
                                    .border(
                                        width = if (themeColor == color) 3.dp else 1.dp,
                                        color = if (themeColor == color) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { viewModel.setThemeColor(color) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (themeColor == color) {
                                    Icon(Icons.Filled.Check, contentDescription = colorLabel(color))
                                }
                            }
                        }
                    }
                    Text(
                        "影响按钮、开关、选中项等交互控件的强调色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 MingYu实况") },
            text = {
                Text(
                    "把普通视频转成 OPPO/一加相册能识别为「实况」的照片：\n" +
                        "JPEG 段序 [XMP(GCamera+OpCamera) / EXIF(UserComment=oplus_8388608) / MPF / JFIF / ICC] " +
                        "+ 封面帧 + 零间隙拼接的原始 MP4。\n\n" +
                        "封面取视频中点帧，时间戳按视频旋转信息转正；" +
                        "输出目录、大小阈值、重试次数等都可在设置页调整。\n\n" +
                        "版本 $versionName，与桌面版脚本 mp4_to_oppo_livephoto.py 使用同一套字节格式。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun mainList(
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    themeColor: ThemeColor,
    darkTheme: Boolean,
    relativePath: String,
    maxMB: Int,
    includeOversize: Boolean,
    retries: Int,
    versionName: String,
    onOpenSubPage: (SubPage) -> Unit,
    onOpenAbout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(themeColorSwatch(themeColor, darkTheme))
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onOpenSubPage(SubPage.THEME_COLOR) },
        )
    }

    ListItem(
        headlineContent = { Text("深色模式") },
        supportingContent = { Text("当前：${themeModeLabel(themeMode)}") },
        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
        trailingContent = { Chevron() },
        modifier = Modifier.clickable { onOpenSubPage(SubPage.APPEARANCE) },
    )

    ListItem(
        headlineContent = { Text("输出相册目录") },
        supportingContent = { Text("实况照片写入内部存储的该目录，例如 DCIM/Camera") },
        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
    )
    OutlinedTextField(
        value = relativePath,
        onValueChange = { viewModel.setRelativePathDraft(it) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )

    ListItem(
        headlineContent = { Text("视频大小阈值") },
        supportingContent = { Text("当前 $maxMB MB，超过阈值的视频默认跳过") },
        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
    )
    Slider(
        value = maxMB.toFloat(),
        onValueChange = { viewModel.setMaxMB(it.toInt()) },
        valueRange = 5f..200f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    )

    ListItem(
        headlineContent = { Text("包含超大视频") },
        supportingContent = { Text("打开后忽略上面的阈值，全部转换（耗时更长）") },
        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
        trailingContent = {
            Switch(checked = includeOversize, onCheckedChange = { viewModel.setIncludeOversize(it) })
        },
    )

    ListItem(
        headlineContent = { Text("失败重试次数") },
        supportingContent = { Text("当前 $retries 次，单个视频抽帧或写库失败时自动重试") },
        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (0..3).forEach { count ->
            FilterChip(
                selected = retries == count,
                onClick = { viewModel.setRetries(count) },
                label = { Text("$count 次") },
            )
        }
    }

    ListItem(
        headlineContent = { Text("关于") },
        supportingContent = { Text("格式说明与写入规则") },
        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
        trailingContent = { Chevron() },
        modifier = Modifier.clickable(onClick = onOpenAbout),
    )

    ListItem(
        headlineContent = { Text("欢迎说明") },
        supportingContent = { Text("首次进入时的功能介绍、授权与 ColorOS 提示") },
        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
        trailingContent = { Chevron() },
        modifier = Modifier.clickable { viewModel.reopenIntro() },
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("ColorOS 专用", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "转成实况照片后「创建时间」不会改变，一加 15 + ColorOS 17 测试通过。\n" +
                    "写这个原因是某人 vivo 换机到 OPPO 后 6000 张实况变成了 2 秒小视频。\n" +
                    "本 APP 120% 代码由 AI 编写。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    val context = LocalContext.current
    ListItem(
        headlineContent = { Text("GitHub") },
        supportingContent = { Text("github.com/mingyu0401") },
        leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
        trailingContent = { Chevron() },
        modifier = Modifier.clickable { openGitHub(context) },
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ListItem(
            headlineContent = { Text("版本") },
            supportingContent = { Text(versionName) },
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun subPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        content = content,
    )
}

private fun themeModeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.FOLLOW_SYSTEM -> "跟随系统"
}

private fun colorLabel(color: ThemeColor) = when (color) {
    ThemeColor.GREEN -> "翠绿"
    ThemeColor.PURPLE -> "紫色"
    ThemeColor.BLUE -> "蓝色"
    ThemeColor.ORANGE -> "橙色"
    ThemeColor.PINK -> "粉色"
}
