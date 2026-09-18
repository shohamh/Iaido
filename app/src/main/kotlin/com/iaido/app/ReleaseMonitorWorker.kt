package com.iaido.app

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class ReleaseMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val nowMs = System.currentTimeMillis()
        val channel = selectedChannel(context)
        val releaseClient = GitHubReleaseMonitorClient()
        var state = loadState(context)
        val probe = runCatching { releaseClient.latestRelease(channel) }.getOrNull()
        val workflowStatus = releaseClient.releaseWorkflowStatus()

        state = when (workflowStatus) {
            is ReleaseWorkflowStatus.Running -> {
                val baseline = state.fastPollingBaseline
                    ?: (probe as? ReleaseProbe.Available)?.identity
                val releasePublished = probe is ReleaseProbe.Available &&
                    (state.fastPollingBaseline == null || probe.identity != baseline)
                if (state.isFastPolling(nowMs) && releasePublished) {
                    state.stopFastPolling()
                } else {
                    state.startFastPolling(nowMs, workflowStatus.runId, baseline)
                }
            }
            ReleaseWorkflowStatus.Idle, ReleaseWorkflowStatus.Unknown -> state.stopFastPolling()
        }

        if (probe is ReleaseProbe.Available &&
            (probe.identity != state.lastStagedRelease || stagedAppUpdateFile(context) == null)
        ) {
            when (val update = AppUpdateClient(context, channel = channel).update()) {
                AppUpdateResult.UpToDate -> state = state.copy(lastStagedRelease = probe.identity)
                is AppUpdateResult.ReadyToInstall -> {
                    val actualIdentity = update.releaseIdentity ?: probe.identity
                    val published = ReleaseNotificationPublisher.publish(
                        context = context,
                        channel = channel,
                        versionName = update.versionName.ifBlank { probe.versionLabel },
                        identity = actualIdentity,
                    )
                    state = state.copy(
                        lastStagedRelease = actualIdentity,
                        stagedVersionName = update.versionName.ifBlank { probe.versionLabel },
                        lastNotifiedRelease = actualIdentity.takeIf { published },
                    )
                }
                is AppUpdateResult.InstallPermissionRequired -> {
                    val actualIdentity = update.releaseIdentity ?: probe.identity
                    val published = ReleaseNotificationPublisher.publish(
                        context = context,
                        channel = channel,
                        versionName = update.versionName.ifBlank { probe.versionLabel },
                        identity = actualIdentity,
                    )
                    state = state.copy(
                        lastStagedRelease = actualIdentity,
                        stagedVersionName = update.versionName.ifBlank { probe.versionLabel },
                        lastNotifiedRelease = actualIdentity.takeIf { published },
                    )
                }
                is AppUpdateResult.Failed -> Unit
            }
        } else if (probe is ReleaseProbe.Available &&
            probe.identity == state.lastStagedRelease &&
            probe.identity != state.lastNotifiedRelease &&
            stagedAppUpdateFile(context) != null
        ) {
            val published = ReleaseNotificationPublisher.publish(
                context = context,
                channel = channel,
                versionName = state.stagedVersionName.ifBlank { probe.versionLabel },
                identity = state.lastStagedRelease ?: probe.identity,
            )
            if (published) state = state.copy(lastNotifiedRelease = probe.identity)
        }

        saveState(context, state)
        ReleaseMonitorScheduler.schedule(context, ReleasePollingPolicy.nextDelayMs(nowMs, state))
        return Result.success()
    }

    private suspend fun selectedChannel(context: Context): UpdateChannel {
        val preferences = context.settingsStore.data.first()
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        return updateChannelFromStoredValue(preferences[updateChannelKey], versionName)
    }

    private companion object {
        val fastUntilKey = stringPreferencesKey("release_monitor_fast_until")
        val fastBaselineKey = stringPreferencesKey("release_monitor_fast_baseline")
        val fastWorkflowIdKey = stringPreferencesKey("release_monitor_fast_workflow_id")
        val lastStagedKey = stringPreferencesKey("release_monitor_last_staged")
        val stagedVersionNameKey = stringPreferencesKey("release_monitor_staged_version_name")
        val lastNotifiedKey = stringPreferencesKey("release_monitor_last_notified")

        fun loadState(context: Context): ReleaseMonitorState {
            val preferences = context.getSharedPreferences(RELEASE_MONITOR_PREFS, Context.MODE_PRIVATE)
            return ReleaseMonitorState(
                fastPollingUntilMs = preferences.getString(fastUntilKey.name, null)?.toLongOrNull() ?: 0L,
                fastPollingBaseline = decodeIdentity(preferences.getString(fastBaselineKey.name, null)),
                fastPollingWorkflowId = preferences.getString(fastWorkflowIdKey.name, null)?.toLongOrNull(),
                lastStagedRelease = decodeIdentity(preferences.getString(lastStagedKey.name, null)),
                stagedVersionName = preferences.getString(stagedVersionNameKey.name, "").orEmpty(),
                lastNotifiedRelease = decodeIdentity(preferences.getString(lastNotifiedKey.name, null)),
            )
        }

        fun saveState(context: Context, state: ReleaseMonitorState) {
            context.getSharedPreferences(RELEASE_MONITOR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(fastUntilKey.name, state.fastPollingUntilMs.toString())
                .putString(fastBaselineKey.name, encodeIdentity(state.fastPollingBaseline))
                .putString(fastWorkflowIdKey.name, state.fastPollingWorkflowId?.toString())
                .putString(lastStagedKey.name, encodeIdentity(state.lastStagedRelease))
                .putString(stagedVersionNameKey.name, state.stagedVersionName)
                .putString(lastNotifiedKey.name, encodeIdentity(state.lastNotifiedRelease))
                .apply()
        }

    }
}

internal const val RELEASE_MONITOR_PREFS = "release-monitor"

internal fun stagedReleaseIdentity(context: Context): ReleaseIdentity? =
    decodeIdentity(
        context.getSharedPreferences(RELEASE_MONITOR_PREFS, Context.MODE_PRIVATE)
            .getString("release_monitor_last_staged", null),
    )

internal fun clearReleaseMonitorState(context: Context) {
    context.getSharedPreferences(RELEASE_MONITOR_PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

internal fun encodeIdentity(identity: ReleaseIdentity?): String? = identity?.let {
    listOf(it.channel.name, it.tagName, it.releaseId, it.assetUpdatedAt).joinToString("\u001f")
}

internal fun decodeIdentity(value: String?): ReleaseIdentity? {
    val parts = value?.split('\u001f') ?: return null
    if (parts.size != 4) return null
    return runCatching {
        ReleaseIdentity(UpdateChannel.valueOf(parts[0]), parts[1], parts[2].toLong(), parts[3])
    }.getOrNull()
}
