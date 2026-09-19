package com.iaido.app

import android.app.Application
import java.io.File

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
            installTelemetryCrashHandler()
        }
    }

    private fun installTelemetryCrashHandler() {
        runCatching {
            val crashFile = File(File(filesDir, "telemetry"), "diagnostics-crash.json")
            TelemetryCrashHandler.install(
                enabled = DiagnosticsTelemetryProvider::isEnabled,
                crashStore = BoundedFileCrashStore(crashFile),
                breadcrumbs = { DiagnosticsTelemetryProvider.instance?.breadcrumbs() ?: emptyList() },
            )
        }
    }
}
