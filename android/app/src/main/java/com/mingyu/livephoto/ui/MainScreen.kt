package com.mingyu.livephoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.mingyu.livephoto.ui.convert.ConvertScreen
import com.mingyu.livephoto.ui.convert.ConvertViewModel
import com.mingyu.livephoto.ui.settings.SettingsScreen
import com.mingyu.livephoto.ui.settings.SettingsViewModel

/** 底部 Tab 对应的 Pager 页序号 */
private object Tabs {
    const val CONVERT = 0
    const val SETTINGS = 1
    const val PAGE_COUNT = 2
}

/**
 * 应用主骨架：与 EMOO 一致的纵向 9:1 划分——上部内容区用 HorizontalPager 左右滑动
 * 切换「转换 / 设置」两页（两页常驻组合，切换不丢状态），下部为主导航栏。
 */
@Composable
fun MingYuApp(convertViewModel: ConvertViewModel, settingsViewModel: SettingsViewModel) {
    val pagerState = rememberPagerState(initialPage = Tabs.CONVERT) { Tabs.PAGE_COUNT }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(9f)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    Tabs.CONVERT -> ConvertScreen(
                        convertViewModel = convertViewModel,
                        settingsViewModel = settingsViewModel,
                    )

                    else -> SettingsScreen(viewModel = settingsViewModel)
                }
            }
        }
        BottomBar(
            currentPage = pagerState.currentPage,
            modifier = Modifier.weight(1f),
            onTabSelected = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
        )
    }
}

/** 主导航栏：选中项用 secondaryContainer 高亮，点击与滑动双向联动 */
@Composable
private fun BottomBar(currentPage: Int, modifier: Modifier = Modifier, onTabSelected: (Int) -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxSize()) {
            BottomBarItem(
                page = Tabs.CONVERT,
                label = "转换",
                selectedIcon = Icons.Filled.PlayArrow,
                unselectedIcon = Icons.Outlined.PlayArrow,
                selected = currentPage == Tabs.CONVERT,
                modifier = Modifier.weight(1f),
                onTabSelected = onTabSelected,
            )
            BottomBarItem(
                page = Tabs.SETTINGS,
                label = "设置",
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                selected = currentPage == Tabs.SETTINGS,
                modifier = Modifier.weight(1f),
                onTabSelected = onTabSelected,
            )
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    page: Int,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .fillMaxHeight()
            .clickable { onTabSelected(page) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.padding(bottom = 1.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = contentColor,
            maxLines = 1,
        )
    }
}
