package com.iaido.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReleaseInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channel = intent.getStringExtra(RELEASE_NOTIFICATION_CHANNEL_EXTRA)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        val client = AppUpdateClient(this, channel = channel)
        val announcedIdentity = announcedReleaseIdentity(channel)
        val apk = stagedAppUpdateFile(this)
            ?.takeIf { announcedIdentity == null || announcedIdentity == stagedReleaseIdentity(this) }
        if (apk == null) {
            lifecycleScope.launch {
                when (val result = withContext(Dispatchers.IO) { client.update() }) {
                    is AppUpdateResult.ReadyToInstall -> startInstall(client, result.apk)
                    is AppUpdateResult.InstallPermissionRequired -> openInstallPermission()
                    else -> startActivity(Intent(this@ReleaseInstallActivity, SettingsActivity::class.java))
                }
                finish()
            }
        } else {
            startInstall(client, apk)
            finish()
        }
    }

    private fun announcedReleaseIdentity(channel: UpdateChannel): ReleaseIdentity? {
        if (!intent.hasExtra(RELEASE_NOTIFICATION_TAG_EXTRA) ||
            !intent.hasExtra(RELEASE_NOTIFICATION_ID_EXTRA) ||
            !intent.hasExtra(RELEASE_NOTIFICATION_ASSET_UPDATED_AT_EXTRA)
        ) {
            return null
        }
        return ReleaseIdentity(
            channel = channel,
            tagName = intent.getStringExtra(RELEASE_NOTIFICATION_TAG_EXTRA).orEmpty(),
            releaseId = intent.getLongExtra(RELEASE_NOTIFICATION_ID_EXTRA, Long.MIN_VALUE),
            assetUpdatedAt = intent.getStringExtra(RELEASE_NOTIFICATION_ASSET_UPDATED_AT_EXTRA).orEmpty(),
        ).takeIf { it.tagName.isNotBlank() && it.releaseId != Long.MIN_VALUE }
    }

    private fun startInstall(client: AppUpdateClient, apk: java.io.File) {
        if (!packageManager.canRequestPackageInstalls()) {
            openInstallPermission()
        } else {
            startActivity(client.installIntent(apk))
        }
    }

    private fun openInstallPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}
