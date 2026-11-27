package com.luogen.ghpro

import android.app.Application
import android.content.Context
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Favorites
import com.luogen.ghpro.sync.CrashReporter
import com.luogen.ghpro.sync.Notifier
import com.luogen.ghpro.sync.SyncScheduler
import com.luogen.ghpro.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.luogen.ghpro.sync.WatchService
import com.luogen.ghpro.download.Downloader

object AppCtx {
    lateinit var app: Application
    fun get(): Context = app
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.app = this
        Cfg.init(this)
        Favorites.init(this)
        Downloader.markInterrupted()
        Api.token = Cfg.token
        Notifier.createChannels(this)
        CrashReporter.install(this)
        SyncScheduler.schedule(this)
        // App 冷启动立即补一次同步：后台被清理期间漏掉的提及/指派/Star 仓库被删，也能马上弹系统通知
        if (!Cfg.token.isNullOrBlank()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { SyncEngine.syncOnce(this@App, true) }
            }
        }
        if (Cfg.watchEnabled && !Cfg.token.isNullOrBlank()) WatchService.start(this)
        if (Cfg.token.isNullOrBlank().not() && CrashReporter.lastCrash(this) != null) {
            CrashReporter.autoUpload(this)
        }
    }
}
