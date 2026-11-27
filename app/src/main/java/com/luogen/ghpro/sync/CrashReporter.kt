package com.luogen.ghpro.sync

import android.app.Application
import android.content.Context
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃取证：任何未捕获异常都会写进私有目录，并在下次启动时自动作为 Issue 上报到
 * 你自己的仓库（luogen-vip/GitHubPro），方便远程定位真实崩溃原因。
 * 同时自动开启「安全模式」，避免一进首页就被系统杀掉、什么都没法操作。
 */
object CrashReporter {

    private const val FILE_LAST = "crash_last.txt"
    private const val FILE_HISTORY = "crash_history.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val body = buildString {
                    appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
                    appendLine("thread=${t.name}")
                    appendLine("version=${app.packageManager.getPackageInfo(app.packageName, 0).versionName}")
                    appendLine("android=${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
                    appendLine("safeMode=${Cfg.safeMode}")
                    appendLine()
                    append(sw.toString().take(20000))
                }
                File(app.filesDir, FILE_LAST).writeText(body)
                val hf = File(app.filesDir, FILE_HISTORY)
                val old = if (hf.exists()) hf.readText() else ""
                val merged = (old + "\n\n===== 新崩溃 =====\n" + body).takeLast(200_000)
                hf.writeText(merged)
                Cfg.crashCount = Cfg.crashCount + 1
                Cfg.safeMode = true
            } catch (ignored: Throwable) { }
            try { previous?.uncaughtException(t, e) } catch (ignored: Throwable) { }
        }
    }

    fun lastCrash(ctx: Context): String? =
        try {
            val f = File(ctx.filesDir, FILE_LAST)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) { null }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE_LAST).delete() } catch (e: Exception) { }
    }

    fun history(ctx: Context): String? = try {
        val f = File(ctx.filesDir, FILE_HISTORY)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) { null }

    fun clearHistory(ctx: Context) {
        try { File(ctx.filesDir, FILE_HISTORY).delete() } catch (e: Exception) { }
    }

    /** 后台线程上报，失败就算了，下次启动再试。 */
    fun autoUpload(ctx: Context) {
        val text = lastCrash(ctx) ?: return
        val token = Cfg.token ?: return
        val repo = Cfg.crashRepo
        if (repo.isBlank() || !repo.contains("/")) return
        Thread {
            try {
                Api.token = token
                val parts = repo.split("/")
                val title = "崩溃日志 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
                kotlinx.coroutines.runBlocking {
                    Api.createIssue(parts[0], parts[1], title,
                        "### 自动上报的崩溃日志\n\n```\n" + text.take(15000) + "\n```\n",
                        listOf("bug"), null)
                }
                clear(ctx)
            } catch (e: Throwable) { }
        }.start()
    }
}
