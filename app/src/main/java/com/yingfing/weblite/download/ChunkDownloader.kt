package com.yingfing.weblite.download

import android.os.SystemClock
import com.yingfing.weblite.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 类 aria2 分段下载器（多线程 Range 分片 + 自动降级单线程）。
 * TV 盒子无通知栏，进度通过回调交给 UI 对话框展示。
 */
object ChunkDownloader {

    data class Request(
        val url: String,
        val suggestedName: String,   // Content-Disposition 或 URL 推断的文件名
        val mimeType: String?,
        val contentLength: Long,     // WebView 报告的长度，-1 = 未知
    )

    data class Progress(
        val done: Long,
        val total: Long,          // -1 = 未知
        val speedBps: Long,       // 字节/秒
        val threads: Int,         // 实际并发数（0 = 收尾中）
        val finished: Boolean = false,
        val error: String? = null,
    )

    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null

    /** 是否正在下载（UI 用于防止重复弹窗） */
    @Volatile var running: Boolean = false
        private set

    /**
     * 启动下载，保存到 /sdcard/Download/<name>。
     * onSuccess 返回最终文件路径。
     */
    suspend fun start(req: Request, threads: Int = 6, onProgress: (Progress) -> Unit): String? =
        withContext(Dispatchers.IO) {
            if (running) { onProgress(Progress(0, -1, 0, 0, error = "已有下载在进行中")); return@withContext null }
            running = true; cancelled.set(false)
            try {
                doDownload(req, threads, onProgress)
            } catch (t: Throwable) {
                DebugLog.e("下载失败 $req.url", t)
                onProgress(Progress(0, -1, 0, 0, finished = true, error = t.message ?: "下载失败"))
                null
            } finally {
                running = false
            }
        }

    fun cancel() { cancelled.set(true); job?.cancel() }

    // ---------- 内部实现 ----------

    private suspend fun doDownload(req: Request, threads: Int, onProgress: (Progress) -> Unit): String? {
        val dir = File("/sdcard/Download").apply { mkdirs() }
        if (!dir.canWrite()) throw IOException("外部存储不可写")
        val name = uniqueName(dir, sanitize(req.suggestedName.ifBlank { "download.bin" }))
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        DebugLog.i("下载开始 ${req.url} → ${part.absolutePath}")

        // 探测：Content-Length + Range 支持
        val probe = probe(req.url)
        val total = probe.contentLength
        val rangeOk = probe.acceptRanges && total > 0
        val n = if (rangeOk) threads else 1
        DebugLog.i("下载探测 total=$total rangeOk=$rangeOk threads=$n")

        coroutineScope {
            val done = AtomicLong(0)
            if (rangeOk && total > 0) {
                // 预分配文件
                RandomAccessFile(part, "rw").use { it.setLength(total) }
                val step = total / n
                val jobs = mutableListOf<Job>()
                for (i in 0 until n) {
                    val from = i.toLong() * step
                    val to = if (i == n - 1) total - 1 else (i + 1) * step - 1
                    jobs += launch(Dispatchers.IO) {
                        downloadRange(req.url, from, to, part, done, i)
                    }
                }
                // 进度上报协程
                launch(Dispatchers.IO) {
                    var last = 0L; var lastT = SystemClock.elapsedRealtime()
                    while (isActive && !cancelled.get()) {
                        SystemClock.sleep(500)
                        val now = SystemClock.elapsedRealtime()
                        val cur = done.get()
                        onProgress(Progress(cur, total, ((cur - last) * 1000) / (now - lastT + 1), n))
                        last = cur; lastT = now
                    }
                }
                jobs.forEach { it.join() }
            } else {
                // 单线程流式（服务器不支持 Range 或长度未知）
                job = launch(Dispatchers.IO) { downloadStream(req.url, part, done) }
                var last = 0L; var lastT = SystemClock.elapsedRealtime()
                while (isActive && job?.isActive == true && !cancelled.get()) {
                    SystemClock.sleep(500)
                    val now = SystemClock.elapsedRealtime()
                    val cur = done.get()
                    onProgress(Progress(cur, total, ((cur - last) * 1000) / (now - lastT + 1), 1))
                    last = cur; lastT = now
                }
                job?.join()
            }
            if (cancelled.get()) {
                part.delete()
                DebugLog.i("下载已取消 $name")
                onProgress(Progress(done.get(), total, 0, 0, finished = true, error = "已取消"))
                return@coroutineScope
            }
            if (total > 0 && done.get() != total) throw IOException("分片不完整 ${done.get()}/$total")
        }
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        DebugLog.i("下载完成 ${target.absolutePath}（${target.length()} 字节）")
        onProgress(Progress(target.length(), target.length(), 0, 0, finished = true))
        return target.absolutePath
    }

    private class Probe(val contentLength: Long, val acceptRanges: Boolean)

    private fun probe(url: String): Probe = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            instanceFollowRedirects = true
            requestMethod = "HEAD"
        }
        val len = c.contentLengthLong
        val ranges = c.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true
        c.disconnect()
        if (len > 0) return@runCatching Probe(len, ranges)
        // HEAD 不给长度时，用 Range: 0-0 探测
        val g = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Range", "bytes=0-0")
        }
        val code = g.responseCode
        val cr = g.getHeaderField("Content-Range") // bytes 0-0/12345
        val total = cr?.substringAfter('/')?.toLongOrNull() ?: -1L
        g.disconnect()
        Probe(total, code == 206 && total > 0)
    }.getOrDefault(Probe(-1, false))

    /** 单个 Range 分片，失败自动重试 */
    private fun downloadRange(url: String, from: Long, to: Long, file: File, done: AtomicLong, idx: Int) {
        val expected = to - from + 1
        for (attempt in 1..3) {
            if (cancelled.get()) return
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("Range", "bytes=$from-$to")
                }
                if (c.responseCode != 206) throw IOException("分片 $idx 响应码 ${c.responseCode}")
                c.inputStream.use { input ->
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            if (cancelled.get()) return
                            val r = input.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                            written += r; done.addAndGet(r.toLong())
                        }
                        if (written != expected) throw IOException("分片 $idx 不完整 $written/$expected")
                        return
                    }
                }
            } catch (t: IOException) {
                DebugLog.w("分片 $idx 第 $attempt 次失败: ${t.message}")
                if (attempt == 3) throw t
                SystemClock.sleep(1000L * attempt)
            }
        }
    }

    /** 不支持 Range / 长度未知的单线程下载 */
    private fun downloadStream(url: String, file: File, done: AtomicLong) {
        var attempt = 0
        var offset = done.get()
        while (attempt < 3 && !cancelled.get()) {
            attempt++
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    instanceFollowRedirects = true
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                c.inputStream.use { input ->
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) return
                            val r = input.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                            offset += r; done.addAndGet(r.toLong())
                        }
                        return
                    }
                }
            } catch (t: IOException) {
                DebugLog.w("流式下载第 $attempt 次失败: ${t.message}")
                if (attempt == 3) throw t
                SystemClock.sleep(1000L * attempt)
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download.bin" }

    /** 同名文件自动加序号：a.apk → a(1).apk */
    private fun uniqueName(dir: File, name: String): String {
        var n = name
        var i = 1
        while (File(dir, "$n.part").exists() || File(dir, n).exists()) {
            val dot = name.lastIndexOf('.')
            n = if (dot > 0) "${name.substring(0, dot)}(${i})${name.substring(dot)}" else "$name(${i})"
            i++
        }
        return n
    }
}
