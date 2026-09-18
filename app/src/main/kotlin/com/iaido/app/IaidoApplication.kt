package com.iaido.app

import android.app.Application

class IaidoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseMonitorScheduler.schedule(this, 0L)
    }
}
