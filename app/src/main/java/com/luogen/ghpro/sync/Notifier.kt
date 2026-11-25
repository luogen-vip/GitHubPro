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
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "后台监听", NotificationManager.IMPORTANCE_MIN).apply {
            description = "实时同步服务常驻通知"
        })
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun actionDone(ctx: Context, title: String, body: String, repo: String?, runUrl: String? = null) {
        if (!Cfg.notifyEnabled) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_repo", repo)
            putExtra("open_url", runUrl)
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

    fun generic(ctx: Context, title: String, body: String, id: Int = (System.currentTimeMillis() % 100000).toInt()) {
        if (!Cfg.notifyEnabled) return
        val n = NotificationCompat.Builder(ctx, CH_NOTIF)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(ctx))
            .build()
        safeNotify(ctx, n, id)
    }

    fun downloadProgress(ctx: Context, name: String, progress: Int) {
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("正在下载 $name")
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(ctx, n, name.hashCode())
    }

    fun downloadDone(ctx: Context, name: String, path: String) {
        val n = NotificationCompat.Builder(ctx, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_small)
            .setContentTitle("下载完成")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .build()
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
