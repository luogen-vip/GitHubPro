package com.luogen.ghpro.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.luogen.ghpro.data.Cfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台实时监听服务：即使 App 在后台，也按设定间隔拉取工作流状态与通知，
 * 工作流一结束立刻本地通知。
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.value = true
        startForeground(1001, Notifier.serviceNotification(this, "正在实时监听工作流与通知"))
        job = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    // 每轮只拉通知；每隔约 60 秒才做一次全量仓库/工作流扫描。
                    // 这样通知能 2 秒巡检，又不会每 2 秒把几十个仓库全请求一遍。
                    val full = tick % 30 == 0
                    val changes = SyncEngine.syncOnce(this@WatchService, true, full)
                    tick++
                    running.value = true
                    lastText.value = if (changes.isEmpty()) "刚刚同步 · 无新变化" else "发现 ${changes.size} 条新变化"
                } catch (e: Exception) {
                    lastText.value = "同步失败：${e.message}"
                }
                delay((Cfg.pollSeconds.coerceIn(2, 300)) * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.luogen.ghpro.STOP_WATCH"
        val running = MutableStateFlow(false)
        val lastText = MutableStateFlow("")

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
