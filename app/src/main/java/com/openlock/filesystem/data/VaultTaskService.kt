package com.openlock.filesystem.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class VaultTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Vault Operation in Progress"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Processing files..."
        val currentBytes = intent?.getLongExtra(EXTRA_CURRENT_BYTES, 0L) ?: 0L
        val totalBytes = intent?.getLongExtra(EXTRA_TOTAL_BYTES, 0L) ?: 0L

        when (action) {
            ACTION_START, ACTION_UPDATE -> {
                isTaskRunning = true
                val notification = buildNotification(title, message, currentBytes, totalBytes)
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            ACTION_STOP -> {
                isTaskRunning = false
                val completionTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Vault Operation Complete"
                val completionMessage = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Task finished successfully"
                showCompletionNotification(completionTitle, completionMessage)
                stopForeground(STOP_FOREGROUND_DETACH)
                onTaskFinishedListener?.invoke()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        message: String,
        currentBytes: Long,
        totalBytes: Long
    ): Notification {
        createNotificationChannel()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (totalBytes > 0) {
            val progressPercent = ((currentBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, progressPercent, false)
            val formatStr = String.format(
                Locale.getDefault(),
                "%s (%s / %s)",
                message,
                formatSize(currentBytes),
                formatSize(totalBytes)
            )
            builder.setContentText(formatStr)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun showCompletionNotification(title: String, message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vault Background Operations",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress for long-running encryption, decryption, backup, and import operations."
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "vault_background_operations"

        const val ACTION_START = "com.openlock.filesystem.START_TASK"
        const val ACTION_UPDATE = "com.openlock.filesystem.UPDATE_TASK"
        const val ACTION_STOP = "com.openlock.filesystem.STOP_TASK"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CURRENT_BYTES = "extra_current_bytes"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"

        @Volatile
        var isTaskRunning: Boolean = false

        var onTaskFinishedListener: (() -> Unit)? = null

        fun start(context: Context, title: String, message: String, totalBytes: Long = 0L) {
            isTaskRunning = true
            try {
                val intent = Intent(context, VaultTaskService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_TOTAL_BYTES, totalBytes)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun update(context: Context, title: String, message: String, currentBytes: Long = 0L, totalBytes: Long = 0L) {
            try {
                val intent = Intent(context, VaultTaskService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_CURRENT_BYTES, currentBytes)
                    putExtra(EXTRA_TOTAL_BYTES, totalBytes)
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context, title: String, message: String) {
            try {
                val intent = Intent(context, VaultTaskService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun clearAllNotifications(context: Context) {
            try {
                isTaskRunning = false
                val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.cancelAll()
                val intent = Intent(context, VaultTaskService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1000) return "$bytes B"
            val kb = bytes / 1000.0
            if (kb < 1000) return String.format(Locale.getDefault(), "%.1f KB", kb)
            val mb = kb / 1000.0
            if (mb < 1000) return String.format(Locale.getDefault(), "%.1f MB", mb)
            val gb = mb / 1000.0
            return String.format(Locale.getDefault(), "%.2f GB", gb)
        }
    }
}
