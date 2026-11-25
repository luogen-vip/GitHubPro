package com.luogen.ghpro

import android.app.Application
import android.content.Context
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.sync.CrashReporter
import com.luogen.ghpro.sync.Notifier
import com.luogen.ghpro.sync.SyncScheduler

object AppCtx {
    lateinit var app: Application
    fun get(): Context = app
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.app = this
        Cfg.init(this)
        Api.token = Cfg.token
        Notifier.createChannels(this)
        CrashReporter.install(this)
        SyncScheduler.schedule(this)
        if (Cfg.token.isNullOrBlank().not() && CrashReporter.lastCrash(this) != null) {
            CrashReporter.autoUpload(this)
        }
    }
}
