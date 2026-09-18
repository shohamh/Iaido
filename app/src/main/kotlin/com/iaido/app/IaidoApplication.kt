package com.iaido.app

import android.app.Application

internal fun shouldScheduleReleaseMonitor(processName: String, applicationPackageName: String): Boolean =
    processName == applicationPackageName

class IaidoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (shouldScheduleReleaseMonitor(Application.getProcessName(), packageName)) {
            ReleaseMonitorScheduler.schedule(this, 0L)
        }
    }
}
