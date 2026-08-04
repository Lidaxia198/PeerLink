package com.peerlink.app.cast

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenDecoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private val queue = LinkedBlockingQueue<Pair<Byte, ByteArray>>(96)
    private var surface: Surface? = null
    private var worker: Thread? = null
    @Volatile
    private var lastConfig: ByteArray? = null
    @Volatile
    private var lastKeyFrame: ByteArray? = null

    private val _videoSize = MutableStateFlow(9f to 16f)
    /** width/height ratio pair for UI letterboxing; defaults to phone portrait. */
    val videoSize: StateFlow<Pair<Float, Float>> = _videoSize.asStateFlow()

    @Synchronized
    fun attachSurface(surface: Surface) {
        if (this.surface === surface && codec != null) return
        releaseCodecLocked()
        this.surface = surface
        if (running.get()) {
            startCodecLocked()
        }
    }

    @Synchronized
    fun detachSurface() {
        releaseCodecLocked()
        this.surface = null
    }

    fun hasStreamConfig(): Boolean = lastConfig != null

    fun peekConfig(): ByteArray? = lastConfig

    fun peekKeyFrame(): ByteArray? = lastKeyFrame

    @Synchronized
    fun prepareForNewStream() {
        queue.clear()
        releaseCodecLocked()
        lastConfig = null
        lastKeyFrame = null
        if (running.get() && surface != null) {
            startCodecLocked()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        synchronized(this) {
            if (surface != null && codec == null) startCodecLocked()
        }
        worker = Thread({ drainInput() }, "ScreenDecoder").also { it.start() }
    }

    @Synchronized
    private fun startCodecLocked() {
        if (codec != null) return
        val output = surface ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, output, null, 0)
            decoder.start()
            codec = decoder
            // Drop stale P-frames; reinject CSD + last IDR so surface swaps aren't black.
            queue.clear()
            lastConfig?.let { queue.offer(ScreenEncoder.FLAG_CONFIG to it) }
            lastKeyFrame?.let { queue.offer(ScreenEncoder.FLAG_KEY to it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Decoder configure failed", t)
            onError(t.message ?: "Decoder failed")
            runCatching { codec?.release() }
            codec = null
        }
    }

    @Synchronized
    private fun releaseCodecLocked() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }

    fun submit(flags: Byte, data: ByteArray) {
        if (!running.get()) return
        when (flags) {
            ScreenEncoder.FLAG_CONFIG -> lastConfig = data
            ScreenEncoder.FLAG_KEY -> lastKeyFrame = data
        }
        // Prefer freshness: keep decode queue extremely short.
        while (queue.size > 1 && flags == ScreenEncoder.FLAG_FRAME) {
            queue.poll()
        }
        queue.offer(flags to data)
    }

    private fun drainInput() {
        while (running.get()) {
            val decoder = synchronized(this) { codec }
            if (decoder == null) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val item = try {
                queue.poll(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            if (!running.get()) break
            val data = item.second
            if (data.isEmpty()) continue
            val flags = item.first
            try {
                val inIndex = decoder.dequeueInputBuffer(20_000)
                if (inIndex < 0) {
                    queue.offer(item)
                    releaseOutputs(decoder)
                    continue
                }
                val buffer = decoder.getInputBuffer(inIndex)
                if (buffer == null) {
                    queue.offer(item)
                    continue
                }
                buffer.clear()
                if (data.size > buffer.capacity()) {
                    Log.w(TAG, "Frame too large for input buffer: ${data.size}")
                    continue
                }
                buffer.put(data)
                val codecFlags = when (flags) {
                    ScreenEncoder.FLAG_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    ScreenEncoder.FLAG_KEY -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                decoder.queueInputBuffer(
                    inIndex,
                    0,
                    data.size,
                    System.nanoTime() / 1000,
                    codecFlags
                )
                releaseOutputs(decoder)
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                // Surface / codec teardown while a dequeue is pending — expected when UI resizes.
                if (msg.contains("cancelled", ignoreCase = true) ||
                    msg.contains("BufferQueue", ignoreCase = true) ||
                    t is IllegalStateException
                ) {
                    Log.w(TAG, "Decode interrupted: $msg")
                    synchronized(this) {
                        if (codec === decoder) releaseCodecLocked()
                    }
                    continue
                }
                Log.e(TAG, "Decode failed", t)
                onError(t.message ?: "Decode failed")
                synchronized(this) { releaseCodecLocked() }
            }
        }
    }

    private fun releaseOutputs(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = decoder.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = decoder.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(1)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(1)
                    _videoSize.value = w.toFloat() to h.toFloat()
                }
                outIndex >= 0 -> {
                    // Keep only the latest frame for lower display latency.
                    var latest = outIndex
                    while (true) {
                        val next = decoder.dequeueOutputBuffer(info, 0)
                        if (next >= 0) {
                            decoder.releaseOutputBuffer(latest, false)
                            latest = next
                        } else {
                            break
                        }
                    }
                    decoder.releaseOutputBuffer(latest, true)
                }
                else -> break
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        queue.offer(ScreenEncoder.FLAG_FRAME to ByteArray(0))
        runCatching { worker?.join(800) }
        synchronized(this) {
            releaseCodecLocked()
            lastConfig = null
            lastKeyFrame = null
        }
        worker = null
    }

    companion object {
        private const val TAG = "ScreenDecoder"
    }
}
