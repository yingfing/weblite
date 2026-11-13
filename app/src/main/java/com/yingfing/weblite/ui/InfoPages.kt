package com.yingfing.weblite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingfing.weblite.WebLiteApp
import com.yingfing.weblite.info.HardwareInfo
import com.yingfing.weblite.util.UrlBox
import kotlinx.coroutines.launch

@Composable
fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            })
        },
    ) { pad -> Column(Modifier.padding(pad)) { content() } }
}

/** 硬件信息页：CPU / GPU / 内存 / 屏幕 / 存储 / WebView。 */
@Composable
fun HardwareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sections = remember { HardwareInfo.collect(context) }
    PageScaffold("硬件信息", onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEach { s ->
                item(key = "${s.title}-head") {
                    Text(
                        s.title,
                        Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(s.rows, key = { "${s.title}-${it.first}" }) { (k, v) ->
                    ListItem(
                        headlineContent = { Text(k) },
                        trailingContent = {
                            Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        },
                    )
                }
                item { HorizontalDivider() }
            }
        }
    }
}

/** 历史记录页。 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val items by WebLiteApp.db.history.recent().collectAsStateWithLifecycle(initialValue = emptyList())
    PageScaffold("历史记录", onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = { scope.launch { WebLiteApp.db.history.clear() } }) {
                Icon(Icons.Filled.Delete, null); Text(" 清空 ")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { h ->
                ListItem(
                    headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1) },
                    supportingContent = { Text(UrlBox.hostOf(h.url), maxLines = 1) },
                    modifier = Modifier.clickable { onOpen(h.url) },
                )
            }
        }
    }
}

/** 书签页。 */
@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val items by WebLiteApp.db.bookmarks.all().collectAsStateWithLifecycle(initialValue = emptyList())
    PageScaffold("书签", onBack) {
        if (items.isEmpty()) {
            Text("还没有书签——浏览时点菜单里的「收藏此页」", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.title.ifBlank { b.url }, maxLines = 1) },
                    supportingContent = { Text(UrlBox.hostOf(b.url), maxLines = 1) },
                    leadingContent = { Icon(Icons.Filled.Star, null) },
                    modifier = Modifier.clickable { onOpen(b.url) },
                )
            }
        }
    }
}

/** 设置页：工具栏位置 / 搜索引擎 / 主页 / 特效。 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = WebLiteApp.settings
    var atTop by remember { mutableStateOf(settings.toolbarAtTop) }
    var engineIdx by remember { mutableStateOf(settings.engineIndex) }
    var homepage by remember { mutableStateOf(settings.homepage) }
    var fancy by remember { mutableStateOf(settings.fancyEffects) }

    PageScaffold("设置", onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("工具栏位置") },
                    supportingContent = { Text("地址栏和按钮条整体放最上方还是最下方（电视上放在底部更好按）") },
                    trailingContent = {
                        Row {
                            FilterChip(selected = atTop, onClick = { atTop = true; settings.toolbarAtTop = true }, label = { Text("顶部") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = !atTop, onClick = { atTop = false; settings.toolbarAtTop = false }, label = { Text("底部") })
                        }
                    },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("搜索引擎") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.yingfing.weblite.util.UrlBox.ENGINES.forEachIndexed { i, e ->
                                FilterChip(
                                    selected = engineIdx == i,
                                    onClick = { engineIdx = i; settings.engineIndex = i },
                                    label = { Text(e.name) },
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("主页") },
                    supportingContent = {
                        OutlinedTextField(homepage, {
                            homepage = it
                            settings.homepage = it
                        }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("特效开关") },
                    supportingContent = { Text("动态取色/模糊等视觉效果（电视上默认关闭以省性能，当前版本无此特效，保留开关）") },
                    trailingContent = {
                        Switch(checked = fancy, onCheckedChange = { fancy = it; settings.fancyEffects = it })
                    },
                )
            }
        }
    }
}
