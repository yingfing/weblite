package com.yingfing.weblite.github

import android.net.SSLCertificateSocketFactory
import android.os.SystemClock
import android.util.Log
import com.yingfing.weblite.util.DebugLog
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * GitHub IP 优选直连（移植自 yingfing/fastgithub-cli 的核心思路）：
 * - DoH 解析域名拿候选 IP（对齐 DomainResolver 的多候选思路）
 * - TCP connect 测速排序、持久化缓存、失败拉黑带过期（对齐 IPAddressService 的 AddressElapsed 缓存）
 * - 直连优选 IP:443，TLS SNI=真实域名，证书校验按 SAN 覆盖域名判断（对齐 HttpClientHandler.ValidateServerCertificate：
 *   直连的是真 GitHub 服务器，证书链天然可信，仅主机名与 IP 不匹配需按 SAN 白名单放行）
 */
object GithubAccelerator {

    /** 下载/资源类 GitHub 域名（对齐 appsettings.github.json 的域名覆盖范围） */
    private val GITHUB_HOST_SUFFIXES = listOf(
        "github.com", "githubusercontent.com", "github.io", "githubassets.com", "githubapp.com",
    )

    /** 页面资源类路径前缀：WebView 拦截重定向镜像用 */
    private val ACCELERATABLE_PATHS = listOf(
        "/releases/download/", "/archive/", "/releases/tag/",  // releases + source archive
    )

    /** 资源类整域 */
    private val ACCELERATABLE_HOSTS = listOf(
        "raw.githubusercontent.com", "gist.githubusercontent.com",
        "objects.githubusercontent.com", "codeload.github.com",
    )

    /** 镜像前缀（按优先级，对应 ghproxy 系可用实例） */
    val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

    private const val CONNECT_TIMEOUT_MS = 4000      // 对齐 connectTimeout 的紧凑版
    private const val POOL_TTL_MS = 6 * 60 * 60 * 1000L   // 优选池过期 6h（对齐 normalElapsedExpiration 的持久化思路）
    private const val BLACKLIST_MS = 10 * 60 * 1000L      // 失败 IP 拉黑 10min
    private const val MAX_IPS = 4                          // 对齐 MAX_IP_COUNT=3，稍放宽

    // host → (按测速排序的 IP, 测速时间)
    private val pool = HashMap<String, Pool>()
    // ip → 拉黑截止时间
    private val blacklist = HashMap<String, Long>()
    private val poolFile: File? by lazy { runCatching {
        File(com.yingfing.weblite.WebLiteApp.instance.filesDir, "github_ip_pool.json")
    }.getOrNull() }

    private class Pool(val ips: List<String>, val testedAt: Long)

    fun isGithubHost(host: String): Boolean {
        val h = host.lowercase()
        return GITHUB_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** 该 URL 是否值得拦截重定向（资源/下载类，页面本体不动以免破坏 JS） */
    fun isAcceleratable(url: String): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        val h = u.host.lowercase()
        val p = u.path ?: ""
        return ACCELERATABLE_HOSTS.any { h == it || h.endsWith(".$it") } ||
            (h == "github.com" || h.endsWith(".github.com")) && ACCELERATABLE_PATHS.any { p.contains(it) }
    }

    fun mirrorUrl(url: String, index: Int = 0): String? =
        MIRRORS.getOrNull(index)?.let { it + url }

    /** 取该域名的优选 IP（过期/为空则重新解析测速），失败返回空表 */
    @Synchronized
    fun pickIps(host: String): List<String> {
        val now = System.currentTimeMillis()
        blacklist.entries.removeAll { it.value < now }
        pool.entries.removeAll { now - it.value.testedAt > POOL_TTL_MS }
        pool[host]?.ips?.let { cached ->
            val usable = cached.filterNot { blacklist.containsKey(it) }
            if (usable.isNotEmpty()) return usable
        }
        // 现场解析 + 测速（下载在 IO 线程，允许阻塞）
        val ips = resolveViaDoh(host).ifEmpty {
            runCatching { java.net.InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
        }.distinct()
        val sorted = ips.map { it to probe(it) }
            .filter { it.second != Long.MAX_VALUE && !blacklist.containsKey(it.first) }
            .sortedBy { it.second }
            .take(MAX_IPS)
            .map { it.first }
        DebugLog.i("IP优选 $host → [$sorted]")
        pool[host] = Pool(sorted, now)
        persist(host, sorted, now)
        return sorted
    }

    fun blacklistIp(ip: String) { blacklist[ip] = System.currentTimeMillis() + BLACKLIST_MS }

    /** TCP connect 测速（毫秒），失败返回 MAX_VALUE */
    private fun probe(ip: String): Long = runCatching {
        val s = Socket()
        val t0 = SystemClock.elapsedRealtime()
        s.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
        val cost = SystemClock.elapsedRealtime() - t0
        s.close()
        cost
    }.getOrDefault(Long.MAX_VALUE)

    /** DoH（阿里公共 DNS）解析，绕过本地 DNS 污染 */
    private fun resolveViaDoh(host: String): List<String> = runCatching {
        val c = URL("https://dns.alidns.com/resolve?name=$host&type=A").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/dns-json")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.getJSONObject(i)
            if (a.optInt("type") == 1) a.optString("data").takeIf { it.isNotBlank() } else null
        }
    }.onFailure { Log.w("WebLite", "DoH fail $host: ${it.message}") }.getOrDefault(emptyList())

    /** 持久化优选池 */
    private fun persist(host: String, ips: List<String>, testedAt: Long) {
        runCatching {
            val f = poolFile ?: return
            val root = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            root.put(host, JSONObject().put("ips", org.json.JSONArray(ips)).put("testedAt", testedAt))
            f.writeText(root.toString())
        }
    }

    init {
        // 启动时恢复持久化池
        runCatching restore@{
            val f = poolFile ?: return@restore
            if (!f.exists()) return@restore
            val root = JSONObject(f.readText())
            val now = System.currentTimeMillis()
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                if (now - o.optLong("testedAt") > POOL_TTL_MS) continue
                val arr = o.optJSONArray("ips") ?: continue
                pool[key] = Pool((0 until arr.length()).map { arr.getString(it) }, o.optLong("testedAt"))
            }
        }
    }

    // ---------- TLS 直连（HttpClientHandler.ConnectCallback 的 Java 版） ----------

    /**
     * 建立到 host 的 TLS 连接：TCP 连优选 IP，SNI=真实域名，校验证书 SAN 覆盖域名。
     * 全部 IP 失败抛 IOException（调用方降级镜像/直连）。
     */
    fun connectTls(host: String, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): SSLSocket {
        val errors = mutableListOf<String>()
        for (ip in pickIps(host)) {
            runCatching {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(ip, 443), connectTimeoutMs)
                val factory = SSLCertificateSocketFactory.getDefault(connectTimeoutMs) as SSLCertificateSocketFactory
                val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
                ssl.soTimeout = 30000
                val session = ssl.session   // 触发握手
                verifySan(session.peerCertificates.firstOrNull() as? X509Certificate, host)
                DebugLog.i("直连成功 $host ← $ip（${session.protocol}）")
                return ssl
            }.onFailure {
                errors.add("$ip: ${it.message}")
                blacklistIp(ip)
            }
        }
        throw java.io.IOException("优选 IP 全部不可达: ${errors.joinToString("; ")}")
    }

    /** 证书 SAN 覆盖校验（对齐 ValidateServerCertificate 的 NameMismatch 分支） */
    private fun verifySan(cert: X509Certificate?, host: String) {
        if (cert == null) throw java.io.IOException("无服务器证书")
        val h = host.lowercase()
        val names = runCatching { cert.subjectAlternativeNames }.getOrNull()
            ?.filter { it.size >= 2 && it[0] == 2 }
            ?.mapNotNull { (it[1] as? String)?.lowercase() }
            ?: emptyList()
        if (names.isEmpty()) throw java.io.IOException("证书无 SAN")
        val ok = names.any { n -> n == h || (n.startsWith("*.") && h.endsWith(n.substring(1))) }
        if (!ok) throw java.io.IOException("证书 SAN [$names] 不覆盖 $h")
    }
}
