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

    fun downloadStatus(ctx: Context, name: String, status: String, route: String? = null) {
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
        // 点通知直接打开刚下好的文件：APK 弹安装器，图片/视频进 App 内预览
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_file", path)
        }
        val openPi = PendingIntent.getActivity(ctx, name.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD_DONE)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("下载完成 · 100%")
            .setContentText("点这里打开 · " + (publicPath?.takeIf { it.isNotBlank() } ?: name))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n已保存到 ${publicPath ?: path}\n点按通知可直接打开"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(openPi)
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    fun downloadFailed(ctx: Context, name: String, reason: String) {
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
        runCatching { NotificationManagerCompat.from(ctx).cancel(name.hashCode()) }
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
