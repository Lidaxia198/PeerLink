package com.peerlink.app.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.peerlink.app.MainActivity
import com.peerlink.app.R

/**
 * Keeps the process warmer on aggressive OEMs while connected.
 * Must always call startForeground after startForegroundService, even when stopping,
 * or the system will crash the app (ForegroundServiceDidNotStartInTime).
 */
class LinkKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required immediately — even for STOP / failure paths.
        ensureForeground()

        if (intent == null || intent.action == ACTION_STOP) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        createChannel()
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.link_notification_title))
            .setContentText(getString(R.string.link_notification_text))
            .setSmallIcon(R.drawable.ic_stat_peerlink)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed, falling back", t)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_link),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.peerlink.app.action.START_LINK"
        const val ACTION_STOP = "com.peerlink.app.action.STOP_LINK"
        private const val CHANNEL_ID = "peerlink_link"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "LinkKeepAlive"

        fun start(context: Context) {
            try {
                val intent = Intent(context, LinkKeepAliveService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to start keep-alive", t)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LinkKeepAliveService::class.java).apply {
                    action = ACTION_STOP
                }
                // Prefer startService so we don't require another FGS handshake when stopping.
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "stop via startService failed, trying stopService", t)
                runCatching { context.stopService(Intent(context, LinkKeepAliveService::class.java)) }
            }
        }
    }
}
