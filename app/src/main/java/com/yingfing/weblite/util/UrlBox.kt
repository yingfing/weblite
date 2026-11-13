package com.yingfing.weblite.util

/**
 * 地址栏输入 → 可加载的 URL。
 * 规则：
 *  - 已带 scheme（http/https/file/about）→ 原样使用
 *  - 形如域名/IPv4/localhost → 自动补 https://
 *  - 其余 → 按所选搜索引擎搜索
 */
object UrlBox {

    data class Engine(val name: String, val template: String) {
        fun query(q: String) = template.replace("%s", java.net.URLEncoder.encode(q, "UTF-8"))
    }

    val ENGINES = listOf(
        Engine("必应", "https://www.bing.com/search?q=%s"),
        Engine("百度", "https://www.baidu.com/s?wd=%s"),
        Engine("谷歌", "https://www.google.com/search?q=%s"),
        Engine("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    )
    val DEFAULT = ENGINES[0]

    private val KNOWN_SCHEMES = setOf(
        "http", "https", "file", "about", "data", "javascript", "blob", "intent", "ws", "wss",
    )

    /** 输入以已知协议开头 → 原样返回。 */
    private fun hasKnownScheme(q: String): Boolean {
        val i = q.indexOf(':')
        if (i <= 0) return false
        val scheme = q.substring(0, i).lowercase()
        return scheme in KNOWN_SCHEMES && (scheme in setOf("about", "file", "intent") || q.length > i)
    }

    private val LOOKS_LIKE_HOST = Regex(
        """^(localhost(:\d+)?(/.*)?|(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?|[\p{L}0-9-]+(\.[\p{L}0-9-]+)+(:\d+)?(/.*)?)$""",
    )

    fun resolve(input: String, engine: Engine = DEFAULT): String {
        val q = input.trim()
        if (q.isEmpty()) return engine.query("")
        if (hasKnownScheme(q)) return q
        if (LOOKS_LIKE_HOST.matches(q)) return "https://$q"
        return engine.query(q)
    }

    /** 用于展示的 host；无效返回原串。 */
    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}
