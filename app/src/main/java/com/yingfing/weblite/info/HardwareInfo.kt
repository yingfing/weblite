package com.yingfing.weblite.info

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.WebView
import android.view.Display
import javax.microedition.khronos.egl.EGL14
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 硬件信息采集：设备 / CPU / GPU / 内存 / 屏幕 / 存储 / WebView 版本。
 * 全部只读系统信息，不需要任何权限。
 */
object HardwareInfo {

    data class Section(val title: String, val rows: List<Pair<String, String>>)

    fun collect(context: android.content.Context): List<Section> {
        val sections = mutableListOf<Section>()

        // ---- 设备 ----
        sections += Section("设备", listOf(
            "品牌" to Build.MANUFACTURER,
            "型号" to Build.MODEL,
            "系统版本" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "内核" to System.getProperty("os.version") ?: "未知",
            "固件版本" to Build.ID,
        ))

        // ---- CPU ----
        val cpuModel = readCpuModel()
        sections += Section("CPU", listOf(
            "核心数" to "${Runtime.getRuntime().availableProcessors()} 核",
            "架构" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"),
            "型号" to cpuModel,
            "64 位支持" to if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "是" else "否",
        ))

        // ---- GPU ----
        val gpu = queryGpu()
        sections += Section("GPU", listOf(
            "渲染器" to (gpu.renderer ?: "未知"),
            "厂商" to (gpu.vendor ?: "未知"),
            "OpenGL ES" to (gpu.version ?: "未知"),
        ))

        // ---- 内存 ----
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        sections += Section("内存", listOf(
            "总内存" to human(mem.totalMem),
            "可用内存" to human(mem.availMem),
            "低内存阈值" to human(mem.threshold),
            "单应用堆上限" to "${maxHeap} MB",
        ))

        // ---- 屏幕 ----
        sections += Section("屏幕", displayRows(context))

        // ---- 存储 ----
        sections += Section("存储", storageRows())

        // ---- WebView ----
        sections += Section("WebView 内核", listOf(
            "提供方" to (runCatching { WebView.getCurrentWebViewPackage()?.applicationInfo?.loadLabel(context.packageManager)?.toString() }.getOrNull() ?: "未知"),
            "版本" to (runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "未知"),
        ))

        return sections
    }

    // ---------- 私有工具 ----------

    private fun readCpuModel(): String = runCatching {
        java.io.File("/proc/cpuinfo").bufferedReader().useLines { lines ->
            lines.firstOrNull {
                it.startsWith("Hardware") || it.startsWith("model name") || it.startsWith("cpu model")
            }?.substringAfter(':')?.trim() ?: "未提供"
        }
    }.getOrDefault("读取失败")

    /** 通过 EGL 创建离屏 GLES2 上下文读 GPU 字符串（无需 GLSurfaceView）。 */
    private data class Gpu(val renderer: String?, val vendor: String?, val version: String?)

    private fun queryGpu(): Gpu = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return Gpu(null, null, null)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return Gpu(null, null, null)

        val cfgAttr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, num, 0)
        if (num[0] == 0 || cfgs[0] == null) return Gpu(null, null, null)

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) return Gpu(null, null, null)
        if (!EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx)) {
            EGL14.eglDestroyContext(display, ctx)
            return Gpu(null, null, null)
        }

        val r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
        val v = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR)
        val gver = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
        Gpu(r, v, gver)
    }.getOrDefault(Gpu(null, null, null))

    private fun displayRows(context: android.content.Context): List<Pair<String, String>> {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val refresh = wm.defaultDisplay.refreshRate
        val modes = runCatching { wm.defaultDisplay.supportedModes }.getOrDefault(emptyArray())
        val maxRefresh = modes.maxOfOrNull { it.refreshRate } ?: refresh
        val hdr = runCatching { wm.defaultDisplay.hdrCapabilities?.types?.isNotEmpty() }.getOrDefault(false)
        val diag = Math.hypot(metrics.widthPixels.toDouble(), metrics.heightPixels.toDouble()) / metrics.xdpi
        return listOf(
            "分辨率" to "${metrics.widthPixels} × ${metrics.heightPixels}",
            "刷新率" to "${refresh.toInt()} Hz（最高 ${maxRefresh.toInt()} Hz）",
            "密度" to "${metrics.densityDpi} dpi（${metrics.density}x）",
            "对角线" to "约 %.1f 英寸".format(diag),
            "HDR 支持" to if (hdr == true) "是" else "否/未声明",
            "显示器类型" to displayType(wm.defaultDisplay),
        )
    }

    private fun displayType(d: Display): String = runCatching {
        if (Build.VERSION.SDK_INT >= 30) d.type.toString() else "TV/内置"
    }.getOrDefault("未知")

    private fun storageRows(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            val internal = StatFs(Environment.getDataDirectory().path)
            rows += "内部存储总量" to human(internal.totalBytes)
            rows += "内部存储可用" to human(internal.availableBytes)
        }
        runCatching {
            val ext = Environment.getExternalStorageDirectory()
            val fs = StatFs(ext.path)
            rows += "外部存储总量" to human(fs.totalBytes)
            rows += "外部存储可用" to human(fs.availableBytes)
        }
        return rows
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
}
