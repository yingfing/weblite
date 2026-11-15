package com.yingfing.weblite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yingfing.weblite.browser.TabManager
import com.yingfing.weblite.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 处理外部链接打开（分享/默认浏览器入口）
        val startUrl = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data?.toString() ?: ""

        setContent {
            WebLiteTheme {
                App(startUrl)
            }
        }
    }
}

@Composable
fun App(startUrl: String) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = LocalContext.current

    // 外部链接唤起时先进入迷你浏览器（类 Custom Tabs），展开键切换到完整浏览器
    var lightMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startUrl.isNotBlank()) }

    // 当前是否在浏览器页（返回键交给 WebView 处理）
    val showBrowser = !lightMode && route == "browser"

    androidx.activity.compose.BackHandler(enabled = showBrowser) {
        // 返回键：先退网页历史，退无可退才退出应用
        val tab = TabManager.active.value
        if (tab?.web?.canGoBack() == true) tab.back() else (context as? android.app.Activity)?.finish()
    }

    if (lightMode) {
        LightBrowserScreen(
            startUrl = startUrl,
            onExpand = { lightMode = false },
            onClose = { (context as? android.app.Activity)?.finish() },
        )
        return
    }

    NavHost(nav, startDestination = "browser", Modifier.padding()) {
        composable("browser") {
            BrowserScreen(
                onOpen = { r -> nav.navigate(r) },
            )
        }
        composable("info") { HardwareScreen(onBack = { nav.popBackStack() }) }
        composable("history") {
            HistoryScreen(onBack = { nav.popBackStack() }, onOpen = { u ->
                nav.popBackStack()
                TabManager.active.value?.load(u)
            })
        }
        composable("bookmarks") {
            BookmarksScreen(onBack = { nav.popBackStack() }, onOpen = { u ->
                nav.popBackStack()
                TabManager.active.value?.load(u)
            })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
    // startUrl 处理一次
    LaunchedEffect(startUrl) {
        if (startUrl.isNotBlank()) TabManager.active.value?.load(startUrl)
    }
}
