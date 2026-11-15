package com.yingfing.weblite.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingfing.weblite.browser.TabManager
import com.yingfing.weblite.util.UrlBox

/**
 * 外部链接迷你浏览器（类 Custom Tabs）：
 * 别的 App 点链接进来时，顶部一条窄工具栏（✕ / 展开 / 域名 / 分享 / 菜单）+ 细进度线，
 * 不显示完整浏览器外壳；点展开键进入完整 BrowserScreen（同一个标签页无缝接管）。
 */
@Composable
fun LightBrowserScreen(
    startUrl: String,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val tab = remember { TabManager.current(context) }
    LaunchedEffect(startUrl) {
        if (startUrl.isNotBlank() && tab.url.value.isBlank()) tab.load(startUrl)
    }
    val url by tab.url.collectAsStateWithLifecycle()
    val progress by tab.progress.collectAsStateWithLifecycle()
    val canBack by tab.canGoBack.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (canBack) tab.back() else onClose()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---------- 迷你工具栏 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
            IconButton(onClick = onExpand) { Icon(Icons.Filled.KeyboardArrowDown, "展开完整浏览器") }
            Text(
                UrlBox.hostOf(url).ifBlank { "加载中" },
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }, "分享链接"
                ))
            }) { Icon(Icons.Filled.Share, "分享") }
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "菜单") }
        }
        // ---------- 细进度线 ----------
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        // ---------- 网页 ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> tab.createIfNeed(ctx, TabManager.onLoad) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ---------- 迷你菜单 ----------
    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            title = { Text(UrlBox.hostOf(url), maxLines = 1) },
            text = {
                Column {
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("复制链接") },
                        leadingContent = { Icon(Icons.Filled.Link, null) },
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                            menuOpen = false
                        },
                    )
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("在系统浏览器打开") },
                        leadingContent = { Icon(Icons.Filled.OpenInNew, null) },
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            menuOpen = false
                        },
                    )
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("展开完整浏览器") },
                        leadingContent = { Icon(Icons.Filled.OpenInFull, null) },
                        modifier = Modifier.clickable {
                            menuOpen = false
                            onExpand()
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { menuOpen = false }) { Text("关闭") }
            },
        )
    }
}
