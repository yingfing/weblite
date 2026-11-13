package com.yingfing.weblite.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 一个标签页 = 一个常驻 WebView 实例（切页不重载）。
 */
class Tab(val id: Int) {
    var web: WebView? = null
    var pendingUrl: String = ""

    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(-1)   // -1 = 空闲
    val canGoBack = MutableStateFlow(false)
    val canGoForward = MutableStateFlow(false)

    fun createIfNeed(context: Context, onLoad: (Tab) -> Unit): WebView =
        web ?: newWebView(context, onLoad).also { web = it; load(pendingUrl) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(context: Context, onLoad: (Tab) -> Unit) = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // 非网页协议交给系统（tel:/mailto:/intent:…）
                if (u.scheme !in setOf("http", "https", "file", "about", "data")) return true
                return false
            }
            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                this@Tab.url.value = u
                onLoad(this@Tab)
            }
            override fun onPageFinished(view: WebView, u: String) {
                this@Tab.title.value = view.title ?: u
                this@Tab.url.value = u
                refreshNav(view)
                onLoad(this@Tab)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@Tab.progress.value = if (newProgress >= 100) -1 else newProgress
                refreshNav(view)
            }
        }
    }

    fun refreshNav(view: WebView) {
        canGoBack.value = view.canGoBack()
        canGoForward.value = view.canGoForward()
    }

    fun load(target: String) {
        val w = web
        if (w == null) { pendingUrl = target; url.value = target } else w.loadUrl(target)
    }

    fun back() = web?.goBack()
    fun forward() = web?.goForward()
    fun reload() = web?.reload()
    fun stop() = web?.stopLoading()
    fun destroy() { web?.destroy(); web = null }
}

/** 标签页管理：常驻单例，进程内共享。 */
object TabManager {
    private var seq = 0
    val tabs = MutableStateFlow(listOf<Tab>())
    val active = MutableStateFlow<Tab?>(null)
    var onLoad: (Tab) -> Unit = {}

    fun newTab(startUrl: String): Tab {
        val t = Tab(++seq).apply { pendingUrl = startUrl }
        tabs.value = tabs.value + t
        active.value = t
        return t
    }

    fun close(tab: Tab) {
        tab.destroy()
        val rest = tabs.value - tab
        tabs.value = rest
        if (active.value == tab) active.value = rest.lastOrNull()
    }

    fun current(context: Context): Tab {
        val t = active.value ?: newTab("")
        t.createIfNeed(context, onLoad)
        return t
    }

    fun context(): Context? = active.value?.web?.context
}
