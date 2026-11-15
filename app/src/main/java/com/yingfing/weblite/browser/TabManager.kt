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

    companion object {
        /** UI 层注册的下载回调（整个 App 一个即可） */
        @Volatile var onDownload: ((com.yingfing.weblite.download.ChunkDownloader.Request) -> Unit)? = null

        /** 从 Content-Disposition 或 URL 推断文件名 */
        fun downloadFileName(url: String, contentDisposition: String?): String {
            contentDisposition?.let {
                Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(it)?.groupValues?.get(1)?.let { n ->
                    return java.net.URLDecoder.decode(n, "UTF-8")
                }
            }
            val path = url.substringBefore('#').substringBefore('?')
            val last = path.substringAfterLast('/')
            return if (last.contains('.') && last.isNotBlank()) java.net.URLDecoder.decode(last, "UTF-8") else "download.bin"
        }
    }

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
        // target="_blank" 链接：不开多窗口会被 WebView 静默吞掉（点击无反应），必须开启并接管 onCreateWindow
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // 下载拦截 → 交给 UI 层弹确认框（onDownload 由 BrowserScreen 注册）
        setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            val name = downloadFileName(url, contentDisposition)
            com.yingfing.weblite.util.DebugLog.i("下载请求 $url（${contentLength}字节, $mimeType）")
            onDownload?.invoke(
                com.yingfing.weblite.download.ChunkDownloader.Request(
                    url = url, suggestedName = name, mimeType = mimeType, contentLength = contentLength
                )
            )
        }

        webViewClient = object : WebViewClient() {
            // API 24+ 走这个重载
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return filterScheme(request.url.toString())
            }
            // API <24 只会回调这个老签名，缺了它老设备上非网页协议拦截失效
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return filterScheme(url)
            }
            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                com.yingfing.weblite.util.DebugLog.i("[T${this@Tab.id}] 开始加载 $u")
                this@Tab.url.value = u
                onLoad(this@Tab)
            }
            override fun onPageFinished(view: WebView, u: String) {
                this@Tab.title.value = view.title ?: u
                this@Tab.url.value = u
                refreshNav(view)
                com.yingfing.weblite.util.DebugLog.i("[T${this@Tab.id}] 加载完成 $u（标题=${view.title}）")
                onLoad(this@Tab)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    com.yingfing.weblite.util.DebugLog.e("[T${this@Tab.id}] 加载失败 ${request.url} code=${error.errorCode} ${error.description}")
                }
            }
            // GitHub 资源类请求重定向镜像加速（页面本体不动，设置开关控制）
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url.toString()
                if (!com.yingfing.weblite.WebLiteApp.settings.githubAccelerate) return null
                if (!com.yingfing.weblite.github.GithubAccelerator.isAcceleratable(u)) return null
                val mirror = com.yingfing.weblite.github.GithubAccelerator.mirrorUrl(u) ?: return null
                com.yingfing.weblite.util.DebugLog.i("[T${this@Tab.id}] GitHub 资源加速 → $mirror")
                return android.webkit.WebResourceResponse(
                    "text/plain", "utf-8", 302, "Moved Temporarily",
                    mapOf("Location" to mirror), java.io.ByteArrayInputStream(ByteArray(0))
                )
            }
        }
        webChromeClient = object : WebChromeClient() {
            // 接管 target="_blank" / window.open：TV 单窗口场景直接在本页加载目标地址
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val target = view.hitTestResult.extra ?: view.url
                if (target.isNullOrBlank() || !target.startsWith("http")) {
                    com.yingfing.weblite.util.DebugLog.w("[T${this@Tab.id}] 新窗口请求无法解析目标 $target")
                    return false
                }
                com.yingfing.weblite.util.DebugLog.i("[T${this@Tab.id}] 新窗口 → 本页加载 $target")
                view.loadUrl(target)
                return true
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@Tab.progress.value = if (newProgress >= 100) -1 else newProgress
                refreshNav(view)
            }
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    com.yingfing.weblite.util.DebugLog.i("[T${this@Tab.id} console] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }
    }

    /** 非网页协议（tel:/mailto:/intent:…）交给系统处理 */
    fun filterScheme(url: String): Boolean {
        val scheme = android.net.Uri.parse(url).scheme ?: return true
        if (scheme !in setOf("http", "https", "file", "about", "data")) {
            com.yingfing.weblite.util.DebugLog.w("忽略非网页协议 $url")
            return true
        }
        return false
    }

    fun refreshNav(view: WebView) {
        canGoBack.value = view.canGoBack()
        canGoForward.value = view.canGoForward()
    }

    fun load(target: String) {
        com.yingfing.weblite.util.DebugLog.i("[T$id] 请求加载 $target")
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
        com.yingfing.weblite.util.DebugLog.i("新开标签 #${t.id} → $startUrl")
        return t
    }

    fun close(tab: Tab) {
        com.yingfing.weblite.util.DebugLog.i("关闭标签 #${tab.id}（${tab.url.value}）")
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
