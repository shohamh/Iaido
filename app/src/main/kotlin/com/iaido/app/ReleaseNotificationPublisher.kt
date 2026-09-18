package com.iaido.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal const val RELEASE_NOTIFICATION_CHANNEL_ID = "iaido-release-updates"
internal const val RELEASE_NOTIFICATION_CHANNEL_EXTRA = "release_channel"

internal fun releaseNotificationTitle(channel: UpdateChannel): String =
    "Iaido ${channel.displayName} update available"

internal fun releaseNotificationText(versionName: String): String =
    "Version $versionName is ready to install"

internal object ReleaseNotificationPublisher {
    fun publish(context: Context, channel: UpdateChannel, versionName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        createChannel(context)
        val installIntent = Intent(context, ReleaseInstallActivity::class.java).apply {
            putExtra(RELEASE_NOTIFICATION_CHANNEL_EXTRA, channel.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(channel),
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RELEASE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(releaseNotificationTitle(channel))
            .setContentText(releaseNotificationText(versionName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(releaseNotificationText(versionName)))
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.stat_sys_download_done, "Download & install", pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(channel), notification)
        return true
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            RELEASE_NOTIFICATION_CHANNEL_ID,
            "Iaido updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications when a newer Iaido release is available"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationId(channel: UpdateChannel): Int = when (channel) {
        UpdateChannel.STABLE -> 4101
        UpdateChannel.NIGHTLY -> 4102
    }
}
