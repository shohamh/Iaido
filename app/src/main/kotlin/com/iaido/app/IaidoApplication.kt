package com.iaido.app

import android.app.Application

internal fun isDefaultApplicationProcess(processName: String, applicationPackageName: String): Boolean =
    processName == applicationPackageName

internal fun shouldScheduleReleaseMonitor(processName: String, applicationPackageName: String): Boolean =
    isDefaultApplicationProcess(processName, applicationPackageName)

internal fun shouldInitializeDiagnostics(processName: String, applicationPackageName: String): Boolean =
    isDefaultApplicationProcess(processName, applicationPackageName)

class IaidoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (shouldScheduleReleaseMonitor(Application.getProcessName(), packageName)) {
            ReleaseMonitorScheduler.schedule(this, 0L)
        }
        if (shouldInitializeDiagnostics(Application.getProcessName(), packageName)) {
            initializeDiagnosticsTelemetry()
        }
    }

    private fun initializeDiagnosticsTelemetry() {
        runCatching {
            DiagnosticsTelemetryProvider.initialize(this)
            DiagnosticsTelemetryProvider.instance?.recordAppStart()
            DiagnosticsTelemetryProvider.reportPendingCrash(this)
            installTelemetryCrashHandler()
        }
    }

    private fun installTelemetryCrashHandler() {
        runCatching {
            TelemetryCrashHandler.install(
                enabled = DiagnosticsTelemetryProvider::isEnabled,
                crashStore = BoundedFileCrashStore(DiagnosticsTelemetryProvider.crashFile(this)),
                breadcrumbs = { DiagnosticsTelemetryProvider.instance?.breadcrumbs() ?: emptyList() },
            )
        }
    }
}
