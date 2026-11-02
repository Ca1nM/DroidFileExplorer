package com.sza.androidfolderexplorer.data.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sza.androidfolderexplorer.MainActivity

/**
 * Keeps the process alive and shows a progress notification while a copy, move or
 * compress job runs, so the job survives leaving the app. The work itself runs in the
 * view model; this service only owns the notification.
 */
class JobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                onCancel?.invoke()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Working"
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val notification = build(this, title, text, progress)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_ID = 4001
        private const val ACTION_STOP = "stop"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"

        /** Set by whoever runs the job, so the notification's Cancel button can stop it. */
        @Volatile var onCancel: (() -> Unit)? = null

        /** Start or update the notification. [percent] < 0 shows an indeterminate bar. */
        fun update(context: Context, title: String, text: String, percent: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JobService::class.java)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_PROGRESS, percent)
            )
        }

        fun stop(context: Context) {
            onCancel = null
            context.startService(Intent(context, JobService::class.java).setAction(ACTION_STOP))
        }

        private fun build(context: Context, title: String, text: String, percent: Int): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val cancel = PendingIntent.getService(
                context, 1, Intent(context, JobService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .addAction(0, "Cancel", cancel)
                .build()
        }
    }
}
