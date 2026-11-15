package com.yingfing.weblite.download

import com.yingfing.weblite.github.GithubAccelerator
import com.yingfing.weblite.util.DebugLog
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import javax.net.ssl.SSLSocket

/**
 * GitHub 加速 HTTP 通道（对齐 fastgithub-cli 的降级思路）：
 * 1. IP 优选直连：裸 TLS socket + 手写 HTTP/1.1（Range 精确可控，302 手动跟随，Host 头保留真实域名）
 * 2. 全部优选 IP 失败 → 镜像前缀
 * 3. 镜像失败 → 普通 DNS 直连（HttpURLConnection）
 * 非 GitHub 域名直接走第 3 级。
 */
object GithubHttp {

    class Response(
        val code: Int,
        val headers: Map<String, String>,
        val stream: InputStream,
        private val socket: SSLSocket?,          // 直连模式持有，close 时关 socket
        private val conn: HttpURLConnection?,    // URL 模式持有
    ) {
        fun contentLength(): Long = headers["content-length"]?.toLongOrNull() ?: -1L
        fun contentRangeTotal(): Long =
            headers["content-range"]?.substringAfter('/')?.toLongOrNull() ?: -1L
        fun location(): String? = headers["location"]

        fun close() {
            runCatching { stream.close() }
            runCatching { socket?.close() }
            conn?.disconnect()
        }
    }

    /** 打开 GET（可带 Range），自动跟随 3xx（最多 6 跳），Range 头全程保留 */
    fun open(urlStr: String, range: Long?, onNote: ((String) -> Unit)? = null): Response {
        var current = urlStr
        repeat(6) {
            val resp = openOnce(current, range, onNote)
            if (resp.code in 300..399) {
                val loc = resp.location() ?: throw IOException("重定向无 Location")
                val target = URL(URL(current), loc).toString()
                DebugLog.i("重定向 $current → $target")
                resp.close()
                current = target
                return@repeat
            }
            return resp
        }
        throw IOException("重定向次数超限")
    }

    private fun openOnce(urlStr: String, range: Long?, onNote: ((String) -> Unit)?): Response {
        val u = URL(urlStr)
        val host = u.host

        // 第 1 级：IP 优选直连（仅 GitHub 域名）
        if (GithubAccelerator.isGithubHost(host)) {
            runCatching {
                return viaDirect(u, range)
            }.onFailure { onNote?.invoke("优选直连失败: ${it.message}") }
            // 第 2 级：镜像
            for (i in GithubAccelerator.MIRRORS.indices) {
                val mirror = GithubAccelerator.mirrorUrl(urlStr, i) ?: break
                runCatching {
                    return viaUrlConnection(mirror, range, null)
                }.onFailure { onNote?.invoke("镜像${i + 1}失败: ${it.message}") }
            }
        }
        // 第 3 级：普通直连
        return viaUrlConnection(urlStr, range, onNote)
    }

    /** 手写 HTTP/1.1 over TLS（fastgithub ConnectCallback 的对应物） */
    private fun viaDirect(u: URL, range: Long?): Response {
        val host = u.host
        val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
        val sock = GithubAccelerator.connectTls(host)
        try {
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                if (range != null) append("Range: bytes=$range-\r\n")
                append("Accept: */*\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()
            val reader = java.io.BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val status = reader.readLine() ?: throw IOException("空响应")
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("响应异常: $status")
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            DebugLog.i("直连 HTTP $code $host（content-length=${headers["content-length"]}）")
            return Response(code, headers, BufferedInputStream(sock.getInputStream()), sock, null)
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
    }

    /** HttpURLConnection 通道（镜像 / 普通 DNS） */
    private fun viaUrlConnection(urlStr: String, range: Long?, onNote: ((String) -> Unit)?): Response {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000
            instanceFollowRedirects = false   // 手动跟随，保证 Range 头全程保留
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (range != null) setRequestProperty("Range", "bytes=$range-")
        }
        val code = c.responseCode
        val headers = c.headerFields.mapValues { it.value.firstOrNull() ?: "" }
            .mapKeys { it.key.lowercase() }
        val stream = (if (code in 200..299) c.inputStream else c.errorStream ?: c.inputStream)
        val resp = Response(code, headers, BufferedInputStream(stream), null, c)
        if (code !in 200..299 && code !in 300..399) {
            resp.close()
            throw IOException("HTTP $code")
        }
        return resp
    }

    /** 从 Content-Disposition 推断文件名 */
    fun fileNameFromDisposition(disposition: String?): String? = disposition?.let {
        Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(it)?.groupValues?.get(1)?.let { n ->
            URLDecoder.decode(n, "UTF-8")
        }
    }
}
