package com.peerlink.app.cast

/**
 * Local screen-cast quality presets.
 * Smooth prioritizes low latency; Hd prioritizes sharpness.
 */
enum class CastQuality(
    val label: String,
    val hint: String,
    val maxLongEdge: Int,
    val bitRate: Int,
    val frameRate: Int,
    /** Prefer CBR for realtime Smooth / Balanced. */
    val preferCbr: Boolean
) {
    Smooth(
        label = "流畅",
        hint = "最低延迟，适合帮拍跟手",
        maxLongEdge = 540,
        bitRate = 1_600_000,
        frameRate = 30,
        preferCbr = true
    ),
    Balanced(
        label = "均衡",
        hint = "清晰和流畅兼顾",
        maxLongEdge = 720,
        bitRate = 2_800_000,
        frameRate = 30,
        preferCbr = true
    ),
    Hd(
        label = "高清",
        hint = "更清晰，弱网时可能略卡",
        maxLongEdge = 960,
        bitRate = 4_500_000,
        frameRate = 30,
        preferCbr = false
    );

    companion object {
        fun fromKey(key: String?): CastQuality = when (key?.lowercase()) {
            "balanced", "均衡" -> Balanced
            "hd", "高清" -> Hd
            else -> Smooth
        }
    }
}
