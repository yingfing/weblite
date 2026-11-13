package com.yingfing.weblite.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志：同时写 logcat 和 sdcard 文件，方便电视上无 adb logcat 时排查问题。
 *
 * 文件位置（应用专属外部存储，Android 11+ 无需存储权限即可写）：
 *   /sdcard/Android/data/com.yingfing.weblite/files/logs/weblite.log
 *
 * 查看：adb pull /sdcard/Android/data/com.yingfing.weblite/files/logs/weblite.log
 * 超过 2MB 自动轮换为 weblite.log.old。
 */
object DebugLog {
    private const val TAG = "WebLite"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Volatile private var logFile: File? = null
    @Volatile var enabled: Boolean = true

    fun init(context: Context, onByDefault: Boolean) {
        enabled = onByDefault
        logFile = runCatching {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
                .let { File(it, "weblite.log") }
        }.getOrNull()
        i("---- 会话开始 ---- sdk=${Build.VERSION.SDK_INT} 设备=${Build.MODEL}")
    }

    fun i(msg: String) = log("INFO", msg)
    fun w(msg: String) = log("WARN", msg)
    fun e(msg: String, tr: Throwable? = null) = log("ERROR", msg + (tr?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun log(level: String, msg: String) {
        if (level == "ERROR") Log.e(TAG, msg) else Log.println(Log.INFO, TAG, msg)
        if (!enabled) return
        val f = logFile ?: return
        runCatching {
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "weblite.log.old")
                old.delete()
                f.renameTo(old)
            }
            val ts = fmt.format(Date())
            val line = if (level == "INFO") "$ts $msg\n" else "$ts [$level] $msg\n"
            synchronized(this) { f.appendText(line) }
        }
    }

    /** 当前日志文件路径，给设置页展示用。 */
    fun path(): String = logFile?.absolutePath ?: "不可用"
}
