@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yingfing.weblite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingfing.weblite.WebLiteApp
import com.yingfing.weblite.browser.Tab
import com.yingfing.weblite.browser.TabManager
import com.yingfing.weblite.data.HistoryEntity
import com.yingfing.weblite.util.UrlBox
import kotlinx.coroutines.launch

/**
 * 浏览器主界面。
 * 工具栏（地址栏+按钮条）整体位置由设置决定：置顶或置底，TV 遥控器都够得到。
 */
@Composable
fun BrowserScreen(
    onOpen: (String) -> Unit,   // 路由跳转：info / history / bookmarks / settings
) {
    val context = LocalContext.current
    val settings = WebLiteApp.settings
    // 首次进入：开一个带主页的标签页
    if (TabManager.tabs.value.isEmpty()) TabManager.newTab(settings.homepage)
    val tab = remember { TabManager.current(context) }

    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }

    val url by tab.url.collectAsStateWithLifecycle()
    val title by tab.title.collectAsStateWithLifecycle()
    val progress by tab.progress.collectAsStateWithLifecycle()
    val canBack by tab.canGoBack.collectAsStateWithLifecycle()
    val canFwd by tab.canGoForward.collectAsStateWithLifecycle()

    fun submit(input: String) {
        val target = UrlBox.resolve(input, UrlBox.ENGINES[settings.engineIndex])
        tab.load(target)
        editing = false
    }

    // 地址栏跟随当前页（非编辑状态）
    LaunchedEffect(url) { if (!editing) address = url }

    // 记录历史
    val db = WebLiteApp.db
    LaunchedEffect(url) {
        if (url.startsWith("http")) {
            db.history.add(HistoryEntity(url = url, title = title))
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background),
            verticalArrangement = if (settings.toolbarAtTop) Arrangement.Top else Arrangement.Bottom,
        ) {
            // ---------- 工具栏组（位置可换） ----------
            BrowserBars(
                address = address, onAddressChange = { address = it; editing = true },
                onSubmit = ::submit,
                url = url, title = title,
                canBack = canBack, canFwd = canFwd,
                progress = progress,
                onBack = { if (canBack) tab.back() else Unit },
                onForward = { if (canFwd) tab.forward() else Unit },
                onReload = { tab.reload() },
                onStop = { tab.stop() },
                onHome = { submit(settings.homepage) },
                onTabs = { showTabs = true },
                onMenu = { menuOpen = true },
            )
            if (settings.toolbarAtTop) TopDivider()

            // ---------- 网页 ----------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx -> TabManager.current(ctx).createIfNeed(ctx, TabManager.onLoad) },
                    update = { },
                    modifier = Modifier.fillMaxSize(),
                )
                if (progress >= 0) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
                    )
                }
            }
            if (!settings.toolbarAtTop) TopDivider()
        }

        // ---------- 标签页抽屉 ----------
        if (showTabs) {
            ModalBottomSheet(onDismissRequest = { showTabs = false }) {
                Text("标签页（${TabManager.tabs.value.size}）", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.height(360.dp)) {
                    items(TabManager.tabs.value, key = { it.id }) { t ->
                        val tu by t.url.collectAsStateWithLifecycle()
                        val tt by t.title.collectAsStateWithLifecycle()
                        ListItem(
                            headlineContent = { Text(if (tt.isBlank()) "(空白)" else tt, maxLines = 1) },
                            supportingContent = { Text(UrlBox.hostOf(tu), maxLines = 1) },
                            leadingContent = { if (t == tab) Icon(Icons.Filled.Check, "当前") },
                            trailingContent = {
                                IconButton(onClick = {
                                    TabManager.close(t)
                                    if (TabManager.tabs.value.isEmpty()) TabManager.newTab(settings.homepage)
                                }) { Icon(Icons.Filled.Close, "关闭") }
                            },
                            modifier = Modifier.clickable {
                                TabManager.active.value = t
                                t.createIfNeed(context, TabManager.onLoad)
                                showTabs = false
                            },
                        )
                    }
                }
                Button(
                    onClick = { TabManager.newTab(settings.homepage); showTabs = false },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Icon(Icons.Filled.Add, null); Text(" 新建标签页 ") }
            }
        }

        // ---------- 菜单 ----------
        if (menuOpen) {
            ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    ListItem(headlineContent = { Text(if (desktopMode) "桌面模式：开" else "桌面模式：关") },
                        leadingContent = { Icon(Icons.Filled.DesktopWindows, null) },
                        modifier = Modifier.clickable {
                            desktopMode = !desktopMode
                            tab.web?.settings?.userAgentString =
                                if (desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36" else null
                        })
                    ListItem(headlineContent = { Text("收藏此页") }, leadingContent = { Icon(Icons.Filled.Star, null) },
                        modifier = Modifier.clickable {
                            val dao = db.bookmarks
                            kotlinx.coroutines.MainScope().launch {
                                if (dao.find(url) == null) dao.add(com.yingfing.weblite.data.BookmarkEntity(url = url, title = title))
                            }
                            menuOpen = false
                        })
                    ListItem(headlineContent = { Text("书签") }, leadingContent = { Icon(Icons.Filled.Bookmarks, null) },
                        modifier = Modifier.clickable { menuOpen = false; onOpen("bookmarks") })
                    ListItem(headlineContent = { Text("历史记录") }, leadingContent = { Icon(Icons.Filled.History, null) },
                        modifier = Modifier.clickable { menuOpen = false; onOpen("history") })
                    ListItem(headlineContent = { Text("硬件信息") }, leadingContent = { Icon(Icons.Filled.Memory, null) },
                        modifier = Modifier.clickable { menuOpen = false; onOpen("info") })
                    ListItem(headlineContent = { Text("设置") }, leadingContent = { Icon(Icons.Filled.Settings, null) },
                        modifier = Modifier.clickable { menuOpen = false; onOpen("settings") })
                }
            }
        }
    }
}

@Composable
private fun TopDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** 地址栏 + 按钮条。 */
@Composable
private fun BrowserBars(
    address: String, onAddressChange: (String) -> Unit, onSubmit: (String) -> Unit,
    url: String, title: String,
    canBack: Boolean, canFwd: Boolean, progress: Int,
    onBack: () -> Unit, onForward: () -> Unit,
    onReload: () -> Unit, onStop: () -> Unit, onHome: () -> Unit,
    onTabs: () -> Unit, onMenu: () -> Unit,
) {
    val tabsCount = TabManager.tabs.value.size
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // 地址栏
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            singleLine = true,
            placeholder = { Text("输入网址或搜索词", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Public, null) },
            trailingIcon = {
                IconButton(onClick = { onSubmit(address) }) { Icon(Icons.Filled.ArrowCircleUp, "前往") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        // 按钮条：TV 上按钮大，遥控器好按
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "后退", canBack, onBack)
            IconBtn(Icons.Filled.Home, "主页", true, onHome)
            IconBtn(Icons.AutoMirrored.Filled.ArrowForward, "前进", canFwd, onForward)
            IconBtn(if (progress >= 0) Icons.Filled.Close else Icons.Filled.Refresh, "刷新/停止", true, if (progress >= 0) onStop else onReload)
            IconBtn(Icons.Filled.Tab, "标签页($tabsCount)", true, onTabs)
            IconBtn(Icons.Filled.MoreHoriz, "菜单", true, onMenu)
        }
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(icon, desc, modifier = Modifier.size(28.dp))
    }
}
