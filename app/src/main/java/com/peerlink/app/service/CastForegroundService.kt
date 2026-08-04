package com.peerlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.peerlink.app.MainActivity
import com.peerlink.app.R
import com.peerlink.app.cast.CastQuality
import com.peerlink.app.cast.RemoteShutter
import com.peerlink.app.cast.ScreenEncoder
import com.peerlink.app.network.PeerSessionHolder
import java.util.concurrent.atomic.AtomicBoolean

class CastForegroundService : Service() {
    private var encoder: ScreenEncoder? = null
    private var projection: MediaProjection? = null
    private var currentQuality: CastQuality = CastQuality.Smooth
    private val stopping = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastConfig: ByteArray? = null
    private var encodeW = 720
    private var encodeH = 1280

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MediaProjection cannot be recreated from a sticky restart without the grant Intent.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    failAndStop("缺少投屏授权数据")
                    return START_NOT_STICKY
                }
                val q = CastQuality.fromKey(intent.getStringExtra(EXTRA_QUALITY))
                currentQuality = q
                PeerSessionHolder.setQuality(q)
                startAsForeground()
                startCast(resultCode, data)
            }
            ACTION_UPDATE_QUALITY -> {
                val q = CastQuality.fromKey(intent.getStringExtra(EXTRA_QUALITY))
                if (projection != null && q != currentQuality) {
                    applyQuality(q)
                } else {
                    PeerSessionHolder.setQuality(q)
                    currentQuality = q
                }
            }
            ACTION_REQUEST_KEY -> {
                encoder?.requestKeyFrame()
                mainHandler.postDelayed({ encoder?.requestKeyFrame() }, 160)
                mainHandler.postDelayed({ encoder?.requestKeyFrame() }, 400)
            }
            ACTION_REMOTE_PHOTO -> handleRemotePhoto()
            ACTION_REMOTE_RECORD_START -> handleRemoteRecordStart()
            ACTION_REMOTE_RECORD_STOP -> handleRemoteRecordStop()
            ACTION_STOP -> {
                stopCast(sendStopControl = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        createChannel()
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cast_notification_title))
            .setContentText(getString(R.string.cast_notification_text))
            .setSmallIcon(R.drawable.ic_stat_peerlink)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed, falling back", t)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCast(resultCode: Int, data: Intent) {
        stopping.set(false)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            failAndStop("无法创建 MediaProjection")
            return
        }
        projection = mp
        // Required on Android 14+ before createVirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCast(sendStopControl = true, stopProjection = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, null)

        val session = PeerSessionHolder.session
        if (session == null) {
            failAndStop("会话已断开，无法投屏")
            return
        }

        // Tell peer first, then start encoding — avoids wiping CSD after frames already arrived.
        session.sendControl("start_cast")
        session.sendControl("quality:${currentQuality.name.lowercase()}")
        PeerSessionHolder.setCasting(true)
        PeerSessionHolder.setQuality(currentQuality)

        startEncoder(mp, currentQuality)
    }

    private fun applyQuality(q: CastQuality) {
        val mp = projection ?: return
        currentQuality = q
        PeerSessionHolder.setQuality(q)
        // Tell receiver to reset decoder before the new SPS/PPS arrives.
        PeerSessionHolder.session?.sendControl("quality:${q.name.lowercase()}")
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { encoder?.stop() }
        encoder = null
        // Small gap so VirtualDisplay teardown settles on OEM stacks (vivo/OPPO).
        mainHandler.postDelayed({
            if (projection === mp) {
                startEncoder(mp, q, fatalOnError = false)
            }
        }, 160)
    }

    private fun startEncoder(
        mp: MediaProjection,
        quality: CastQuality,
        fatalOnError: Boolean = true
    ) {
        val metrics = resources.displayMetrics
        val srcW = metrics.widthPixels.coerceAtLeast(16)
        val srcH = metrics.heightPixels.coerceAtLeast(16)
        val scale = minOf(quality.maxLongEdge.toFloat() / maxOf(srcW, srcH), 1f)
        encodeW = ((srcW * scale).toInt().coerceAtLeast(16) / 16) * 16
        encodeH = ((srcH * scale).toInt().coerceAtLeast(16) / 16) * 16

        val encoderInstance = ScreenEncoder(
            mediaProjection = mp,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            quality = quality,
            onEncoded = { flags, bytes ->
                if (flags == ScreenEncoder.FLAG_CONFIG) {
                    lastConfig = bytes
                }
                PeerSessionHolder.session?.sendVideo(flags, bytes)
            },
            onError = { msg ->
                Log.e(TAG, msg)
                if (fatalOnError) {
                    stopCast(sendStopControl = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    Log.w(TAG, "Quality switch failed, cast continues with last working encoder if any")
                }
            }
        )
        encoder = encoderInstance
        mainHandler.post {
            if (projection != null && encoder === encoderInstance) {
                encoderInstance.start()
                encoderInstance.requestKeyFrame()
                mainHandler.postDelayed({ encoderInstance.requestKeyFrame() }, 120)
            }
        }
    }

    /** Only presses shutter; Camera app on this phone saves the file. */
    private fun handleRemotePhoto() {
        if (projection == null) {
            PeerSessionHolder.session?.sendControl("remote_photo_fail:未在投屏")
            return
        }
        pressRemoteShutter(
            onOk = {
                Toast.makeText(this, "已按下快门", Toast.LENGTH_SHORT).show()
                PeerSessionHolder.session?.sendControl("remote_photo_ok")
            },
            onFail = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!RemoteShutter.isReady()) {
                    RemoteShutter.openAccessibilitySettings(this)
                }
                PeerSessionHolder.session?.sendControl("remote_photo_fail:$msg")
            }
        )
    }

    /** Same shutter press — use while system Camera is in video mode. */
    private fun handleRemoteRecordStart() {
        if (projection == null) {
            PeerSessionHolder.session?.sendControl("remote_record_fail:未在投屏")
            return
        }
        pressRemoteShutter(
            onOk = {
                Toast.makeText(this, "已按下快门（录像）", Toast.LENGTH_SHORT).show()
                PeerSessionHolder.session?.sendControl("remote_record_on")
            },
            onFail = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!RemoteShutter.isReady()) {
                    RemoteShutter.openAccessibilitySettings(this)
                }
                PeerSessionHolder.session?.sendControl("remote_record_fail:$msg")
            }
        )
    }

    private fun handleRemoteRecordStop() {
        if (projection == null) {
            PeerSessionHolder.session?.sendControl("remote_record_off")
            return
        }
        pressRemoteShutter(
            onOk = {
                Toast.makeText(this, "已按下快门（停录）", Toast.LENGTH_SHORT).show()
                PeerSessionHolder.session?.sendControl("remote_record_off")
            },
            onFail = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                PeerSessionHolder.session?.sendControl("remote_record_fail:$msg")
            }
        )
    }

    private fun pressRemoteShutter(onOk: () -> Unit, onFail: (String) -> Unit) {
        // Accessibility gestures must run on the service's main thread.
        mainHandler.post {
            val result = RemoteShutter.press()
            if (result.isFailure && !RemoteShutter.isReady()) {
                // Last-ditch OEM broadcast; usually only works with Camera in foreground.
                RemoteShutter.tryCameraButtonBroadcast(this)
            }
            result
                .onSuccess { onOk() }
                .onFailure { onFail(it.message ?: "快门失败") }
        }
    }

    private fun failAndStop(reason: String) {
        Log.e(TAG, reason)
        PeerSessionHolder.setCasting(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun stopCast(sendStopControl: Boolean, stopProjection: Boolean = true) {
        stopping.compareAndSet(false, true)
        mainHandler.removeCallbacksAndMessages(null)
        encoder?.stop()
        encoder = null
        if (stopProjection) {
            runCatching { projection?.stop() }
        }
        projection = null
        val wasCasting = PeerSessionHolder.casting.value
        PeerSessionHolder.setCasting(false)
        if (sendStopControl && wasCasting) {
            PeerSessionHolder.session?.sendControl("stop_cast")
        }
        stopping.set(false)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_cast),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCast(sendStopControl = false, stopProjection = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.peerlink.app.action.START_CAST"
        const val ACTION_STOP = "com.peerlink.app.action.STOP_CAST"
        const val ACTION_UPDATE_QUALITY = "com.peerlink.app.action.UPDATE_CAST_QUALITY"
        const val ACTION_REQUEST_KEY = "com.peerlink.app.action.REQUEST_CAST_KEY"
        const val ACTION_REMOTE_PHOTO = "com.peerlink.app.action.REMOTE_PHOTO"
        const val ACTION_REMOTE_RECORD_START = "com.peerlink.app.action.REMOTE_RECORD_START"
        const val ACTION_REMOTE_RECORD_STOP = "com.peerlink.app.action.REMOTE_RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL_ID = "peerlink_cast"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CastService"
    }
}
