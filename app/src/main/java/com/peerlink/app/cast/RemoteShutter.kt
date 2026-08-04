package com.peerlink.app.cast

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.peerlink.app.service.PeerLinkAccessibilityService

/**
 * Triggers the casting phone's camera shutter. Does not capture or save anything itself.
 */
object RemoteShutter {

    fun isReady(): Boolean = PeerLinkAccessibilityService.isEnabled()

    fun press(): Result<Unit> {
        if (!PeerLinkAccessibilityService.isEnabled()) {
            return Result.failure(
                IllegalStateException("请在投屏手机开启 PeerLink 无障碍（帮拍）")
            )
        }
        return if (PeerLinkAccessibilityService.pressShutter()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("未找到快门，请先打开系统相机"))
        }
    }

    /** Weak fallback some OEMs still honor when Camera is foreground. */
    fun tryCameraButtonBroadcast(context: Context) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA, 0)
            val up = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA, 0)
            context.sendBroadcast(
                Intent(Intent.ACTION_CAMERA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, down)
            )
            context.sendBroadcast(
                Intent(Intent.ACTION_CAMERA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, up)
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
