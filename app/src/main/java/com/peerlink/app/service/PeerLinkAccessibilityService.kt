package com.peerlink.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses the on-screen camera shutter (or taps the typical shutter spot).
 * Saving is left entirely to the system Camera app.
 */
class PeerLinkAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Simulate one shutter press. Returns true if a click/gesture was dispatched. */
    fun pressShutter(): Boolean {
        if (clickShutterNode()) return true
        if (tapTypicalShutterSpot()) return true
        return broadcastCameraButton()
    }

    private fun clickShutterNode(): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        if (Build.VERSION.SDK_INT >= 21) {
            windows?.forEach { w ->
                w.root?.let { roots += it }
            }
        }
        for (root in roots) {
            val hit = findShutter(root)
            if (hit != null && clickNode(hit)) {
                return true
            }
        }
        return false
    }

    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = (node.contentDescription?.toString().orEmpty() + " " +
                node.text?.toString().orEmpty() + " " +
                node.viewIdResourceName.orEmpty()).lowercase()
            val looksLikeShutter = SHUTTER_HINTS.any { desc.contains(it) }
            if (looksLikeShutter && node.isVisibleToUser) {
                if (node.isClickable || node.isEnabled) {
                    return node
                }
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallback
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
        }
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(rect.exactCenterX(), rect.exactCenterY())
        }
        return false
    }

    private fun tapTypicalShutterSpot(): Boolean {
        val dm = resources.displayMetrics
        // Most phone camera UIs put the shutter near bottom-center.
        val x = dm.widthPixels / 2f
        val y = dm.heightPixels * 0.88f
        return tapAt(x, y)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // Do not await the callback on the main thread — that can deadlock with the gesture callback.
        return dispatchGesture(gesture, null, null)
    }

    private fun broadcastCameraButton(): Boolean {
        return runCatching {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA, 0)
            val up = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA, 0)
            sendBroadcast(
                Intent(Intent.ACTION_CAMERA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, down)
                }
            )
            sendBroadcast(
                Intent(Intent.ACTION_CAMERA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, up)
                }
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        @Volatile
        private var instance: PeerLinkAccessibilityService? = null

        private val SHUTTER_HINTS = listOf(
            "快门", "拍照", "拍摄", "照相", "capture", "shutter", "take photo",
            "take picture", "take_picture", "btn_shutter", "shutter_button",
            "camera_shutter", "录制", "录像", "开始录制", "停止录制", "record"
        )

        fun isEnabled(): Boolean = instance != null

        /** @return true if a shutter action was performed */
        fun pressShutter(): Boolean = instance?.pressShutter() ?: false
    }
}
