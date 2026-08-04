package com.peerlink.app.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures screen via MediaProjection, encodes H.264 with low-latency oriented settings.
 */
class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val quality: CastQuality,
    private val onEncoded: (flags: Byte, data: ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            setup()
            drainThread = Thread({ drainLoop() }, "ScreenEncoderDrain").also { it.start() }
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder start failed", t)
            onError(t.message ?: "Encoder failed")
            stop()
        }
    }

    private fun setup() {
        val srcW = width.coerceAtLeast(16)
        val srcH = height.coerceAtLeast(16)
        val scale = minOf(quality.maxLongEdge.toFloat() / maxOf(srcW, srcH), 1f)
        val w = align16((srcW * scale).toInt().coerceAtLeast(16))
        val h = align16((srcH * scale).toInt().coerceAtLeast(16))

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
            // Frequent IDR keeps recoveries fast after drops.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (quality.preferCbr) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
            )
            // Baseline is widely hardware-accelerated and lower latency on OEM phones.
            runCatching {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            }
            runCatching {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
            if (Build.VERSION.SDK_INT >= 23) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
            if (Build.VERSION.SDK_INT >= 29) {
                // Keep a frame flowing so decoder doesn't stall on static camera UI.
                runCatching {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 66_666L)
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            runCatching { setInteger(MediaFormat.KEY_LATENCY, 0) }
            runCatching { setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) }
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            Log.w(TAG, "Configure failed (${quality.label}), retry baseline VBR", t)
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PeerLinkCast",
            w,
            h,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
        // Force an IDR as soon as the VirtualDisplay is live.
        requestKeyFrame()
        Log.i(TAG, "Encoder ${quality.label}: ${w}x$h @ ${quality.frameRate}fps ${quality.bitRate / 1000}kbps")
    }

    /** Ask encoder for an IDR soon (useful after receiver surface swap). */
    fun requestKeyFrame() {
        val c = codec ?: return
        runCatching {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        }
    }

    private fun drainLoop() {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 2_000L
        while (running.get()) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outIndex >= 0 -> {
                    val buffer = encoder.getOutputBuffer(outIndex)
                    if (buffer != null && bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        val data = ByteArray(bufferInfo.size)
                        buffer.get(data)
                        val flags = when {
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> FLAG_CONFIG
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> FLAG_KEY
                            else -> FLAG_FRAME
                        }
                        onEncoded(flags, data)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { drainThread?.join(500) }
        runCatching { virtualDisplay?.release() }
        runCatching {
            codec?.stop()
            codec?.release()
        }
        runCatching { inputSurface?.release() }
        virtualDisplay = null
        codec = null
        inputSurface = null
        drainThread = null
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        const val FLAG_CONFIG: Byte = 1
        const val FLAG_KEY: Byte = 2
        const val FLAG_FRAME: Byte = 3

        private fun align16(v: Int): Int = (v / 16).coerceAtLeast(1) * 16
    }
}
