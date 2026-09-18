package com.iaido.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal const val RELEASE_MONITOR_WORK_NAME = "iaido-release-monitor"

internal object ReleaseMonitorScheduler {
    fun schedule(context: Context, delayMs: Long = RELEASE_POLL_NORMAL_DELAY_MS) {
        val request = OneTimeWorkRequestBuilder<ReleaseMonitorWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RELEASE_MONITOR_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
