package com.luogen.ghpro.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.luogen.ghpro.MainActivity
import com.luogen.ghpro.R
import com.luogen.ghpro.data.Cfg

object Notifier {
    private val canceledIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    const val CH_ACTION = "gh_action"
    const val CH_NOTIF = "gh_notif"
    const val CH_DOWNLOAD = "gh_download"
    const val CH_DOWNLOAD_DONE = "gh_download_done"
    const val CH_SERVICE = "gh_service"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ACTION, "工作流状态", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "工作流成功/失败/取消实时提醒"
        })
        nm.createNotificationChannel(NotificationChannel(CH_NOTIF, "仓库动态", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Star、议题、PR、讨论、通知"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "下载", NotificationManager.IMPORTANCE_LOW).apply {
            description = "发行版/源码/产物下载进度"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "下载成功或失败，带提示音"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "后台监听", NotificationManager.IMPORTANCE_MIN).apply {
            description = "实时同步服务常驻通知"
        })
    }

    private fun contentIntent(ctx: Context, route: String? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!route.isNullOrBlank()) putExtra("open_route", route)
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun actionDone(ctx: Context, title: String, body: String, repo: String?, runUrl: String? = null, route: String? = null) {
        if (!Cfg.notifyEnabled) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_repo", repo)
            putExtra("open_url", runUrl)
            if (!route.isNullOrBlank()) putExtra("open_route", route)
        }
        val pi = PendingIntent.getActivity(ctx, (System.currentTimeMillis() % 10000).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_ACTION)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pi)
            .build()
        safeNotify(ctx, n, (System.currentTimeMillis() % 100000).toInt())
    }

    fun generic(ctx: Context, title: String, body: String,
                route: String? = null,
                id: Int = (System.currentTimeMillis() % 100000).toInt()) {
        if (!Cfg.notifyEnabled) return
        val n = NotificationCompat.Builder(ctx, CH_NOTIF)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(ctx, route, id))
            .build()
        safeNotify(ctx, n, id)
    }

    fun downloadStart(name: String) { canceledIds.remove(name.hashCode()) }

    fun downloadStatus(ctx: Context, name: String, status: String, route: String? = null) {
        if (name.hashCode() in canceledIds) return
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("正在下载 $name")
            .setContentText(status)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(ctx, route, name.hashCode()))
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    fun downloadProgress(ctx: Context, name: String, progress: Int, status: String = "下载中",
                         done: Long = 0L, total: Long = 0L, route: String? = null) {
        if (name.hashCode() in canceledIds) return
        val doneText = fmtBytes(ctx, done)
        val sizeLine = if (total > 0) {
            val left = (total - done).coerceAtLeast(0L)
            "$doneText / ${fmtBytes(ctx, total)} · 还剩 ${fmtBytes(ctx, left)}"
        } else {
            "已下载 $doneText · 服务器未返回总大小"
        }
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("正在下载 $name")
            .setContentText(if (total > 0) "$progress% · $doneText / ${fmtBytes(ctx, total)}" else "已下载 $doneText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (total > 0) "$status · $progress%\n$sizeLine" else "$status\n$sizeLine"))
            .setProgress(100, progress, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(ctx, route, name.hashCode()))
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    private fun fmtBytes(ctx: Context, bytes: Long): String =
        android.text.format.Formatter.formatShortFileSize(ctx, bytes.coerceAtLeast(0L))

    fun downloadDone(ctx: Context, name: String, path: String, publicPath: String? = null) {
        canceledIds.remove(name.hashCode())
        val isApk = name.lowercase().endsWith(".apk") || name.lowercase().contains(".apk.")
        val isMedia = name.substringAfterLast('.', "").lowercase() in
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "mp4", "mkv", "mov", "webm", "avi", "3gp")
        // 点通知先回到 App 校验文件是否还在：已删除/已取消就提示，不会弹安装器或播放器。
        // 校验通过后 APK 走系统安装器，图片/视频走 App 内预览，其他文件交给系统打开。
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_file", path)
        }
        val openPi = PendingIntent.getActivity(ctx, name.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = when {
            isApk -> "立即安装"
            isMedia -> "打开"
            else -> "打开/分享"
        }
        val tip = when {
            isApk -> "点按通知立即安装 · $name"
            isMedia -> "点按通知用 GitHub Pro 打开 · $name"
            else -> "点按通知打开或分享 · $name"
        }
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD_DONE)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle(if (isApk) "下载完成 · APK 安装包" else "下载完成 · 100%")
            .setContentText(tip)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n已保存到 ${publicPath ?: path}\n$tip"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_small, action, openPi)
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    /** 删除记录/删除文件后撤掉对应通知；通知栏里的一条已经不存在，就不能再被点开。 */
    fun clearDownload(ctx: Context, name: String) {
        val id = name.hashCode()
        canceledIds.remove(id)
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
    }

    fun clearDownloads(ctx: Context, names: Collection<String>) {
        names.forEach { clearDownload(ctx, it) }
    }

    fun downloadFailed(ctx: Context, name: String, reason: String) {
        canceledIds.remove(name.hashCode())
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD_DONE)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("下载失败")
            .setContentText(name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n$reason"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(contentIntent(ctx, "downloads", name.hashCode()))
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    fun downloadCanceled(ctx: Context, name: String) {
        canceledIds.add(name.hashCode())
        // 用同一个通知 id 覆盖掉还在转圈的“正在下载”，再变成一条可读的“已取消下载”。
        // 这样用户从下载管理取消后，通知栏不会继续显示一个永远不会完成的任务。
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD_DONE)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("已取消下载")
            .setContentText(name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n下载已取消，后台下载任务已停止"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(ctx, "downloads", name.hashCode()))
            .build()
        // 先撤掉低优先级进度通知，再用完成渠道发“已取消”，避免通知渠道不切换。
        runCatching { NotificationManagerCompat.from(ctx).cancel(name.hashCode()) }
        safeNotify(ctx, n, name.hashCode())
    }

    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("GitHub Pro 实时监听中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent(ctx))
            .build()

    private fun safeNotify(ctx: Context, n: Notification, id: Int) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) { }
    }

}
