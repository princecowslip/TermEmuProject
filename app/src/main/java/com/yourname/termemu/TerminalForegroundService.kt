package com.yourname.termemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

private const val TAG              = "TerminalFgService"
private const val CHANNEL_ID       = "termemu_session"
private const val NOTIFICATION_ID  = 1

/**
 * TerminalForegroundService
 *
 * Keeps the terminal session process alive when the app is backgrounded.
 * Promotes itself to a foreground service (Android requires this for long-running
 * background work on API 26+).
 *
 * Usage:
 *  - Start via [startForegroundService] from MainActivity.
 *  - Stop via [stopService] / [stopSelf] when the session ends.
 *
 * The foreground notification is minimal — a persistent status bar entry that
 * lets the user return to the terminal with a tap.
 */
class TerminalForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-raise the notification on restart (e.g. after the system killed and relaunched us)
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY: the system restarts the service if it's killed, passing a null intent
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Foreground service destroyed.")
    }

    // Services that do not support binding return null here
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW          // silent; no sound or heads-up
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification returns the user to the terminal
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)           // persistent; cannot be swiped away
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
