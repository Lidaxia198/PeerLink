package com.peerlink.app.cast

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.TextureView
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the live receive TextureView so the UI can snapshot without plumbing.
 */
object CastViewHolder {
    @Volatile
    var textureView: TextureView? = null
}

/**
 * Receiver-side capture: JPEG snapshot from the preview, and MP4 from the
 * incoming H.264 elementary stream (same quality as cast, not re-encoded).
 */
class CastCapture(
    private val context: Context,
    /** When true, save into the system Camera album (DCIM/Camera). */
    private val saveToGallery: Boolean = false
) {
    private val recording = AtomicBoolean(false)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var startUs = 0L
    private var lastUs = 0L
    private var pendingFile: File? = null
    private var pendingUri: Uri? = null
    private var width = 720
    private var height = 1280
    private var waitingKey = true

    fun snapshot(mirrored: Boolean = false): Result<Uri> = runCatching {
        val tv = CastViewHolder.textureView ?: error("画面未就绪")
        val raw = tv.bitmap ?: error("无法截取当前画面")
        val bmp = if (mirrored) {
            val m = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it !== raw) raw.recycle()
            }
        } else raw
        try {
            saveImage(bmp)
        } finally {
            bmp.recycle()
        }
    }

    @Synchronized
    fun startRecording(videoW: Int, videoH: Int): Result<Unit> = runCatching {
        if (!recording.compareAndSet(false, true)) error("已在录像中")
        width = videoW.coerceAtLeast(16)
        height = videoH.coerceAtLeast(16)
        waitingKey = true
        started = false
        trackIndex = -1
        startUs = 0L
        lastUs = 0L

        val name = "PeerLink_${stamp()}.mp4"
        val (file, uri) = createVideoTarget(name)
        pendingFile = file
        pendingUri = uri
        val path = file.absolutePath
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        _isRecording.value = true
    }.onFailure {
        cleanupRecording(deleteFile = true)
    }

    @Synchronized
    fun onVideoFrame(flags: Byte, data: ByteArray) {
        if (!recording.get()) return
        val mux = muxer ?: return
        try {
            when (flags) {
                ScreenEncoder.FLAG_CONFIG -> {
                    if (started) return
                    val (sps, pps) = splitSpsPps(data) ?: return
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    trackIndex = mux.addTrack(format)
                    mux.start()
                    started = true
                    startUs = System.nanoTime() / 1000
                    lastUs = startUs
                    waitingKey = true
                }
                ScreenEncoder.FLAG_KEY, ScreenEncoder.FLAG_FRAME -> {
                    if (!started || trackIndex < 0) return
                    if (waitingKey && flags != ScreenEncoder.FLAG_KEY) return
                    waitingKey = false
                    val avcc = annexBToLengthPrefixed(data) ?: return
                    if (avcc.isEmpty()) return
                    val now = System.nanoTime() / 1000
                    // Keep timestamps monotonic; clamp to ~5fps minimum gap if clock jumps.
                    val pts = maxOf(now, lastUs + 1_000)
                    lastUs = pts
                    val info = MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = avcc.size
                        presentationTimeUs = pts - startUs
                        this.flags = if (flags == ScreenEncoder.FLAG_KEY) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                    }
                    mux.writeSampleData(trackIndex, ByteBuffer.wrap(avcc), info)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "record frame failed", t)
        }
    }

    @Synchronized
    fun stopRecording(): Result<Uri> = runCatching {
        if (!recording.get()) error("未在录像")
        val mux = muxer ?: error("录像未开始")
        if (!started) error("还没有写入画面，请稍后再停")
        runCatching { mux.stop() }
        runCatching { mux.release() }
        muxer = null
        recording.set(false)
        _isRecording.value = false

        val file = pendingFile ?: error("文件丢失")
        val uri = finalizeVideo(file, pendingUri)
        pendingFile = null
        pendingUri = null
        started = false
        trackIndex = -1
        uri
    }.onFailure {
        cleanupRecording(deleteFile = true)
    }

    private fun cleanupRecording(deleteFile: Boolean) {
        runCatching { muxer?.release() }
        muxer = null
        recording.set(false)
        _isRecording.value = false
        started = false
        trackIndex = -1
        if (deleteFile) {
            pendingFile?.delete()
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
        pendingFile = null
        pendingUri = null
    }

    private fun saveImage(bitmap: Bitmap): Uri {
        val name = "PeerLink_${stamp()}.jpg"
        val tree = prefsTree()
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(context, tree)
            if (dir != null && dir.canWrite()) {
                val created = dir.createFile("image/jpeg", name)
                    ?: error("无法在所选文件夹创建图片")
                context.contentResolver.openOutputStream(created.uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        error("压缩图片失败")
                    }
                } ?: error("无法写入图片")
                return created.uri
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PeerLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建图片")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) error("压缩图片失败")
            } ?: error("无法写入图片")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PeerLink"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) error("压缩图片失败")
        }
        return Uri.fromFile(file)
    }

    private fun createVideoTarget(name: String): Pair<File, Uri?> {
        val cache = File(context.cacheDir, "cast_record").also { it.mkdirs() }
        val file = File(cache, name)
        if (file.exists()) file.delete()
        return file to null
    }

    private fun finalizeVideo(file: File, existingUri: Uri?): Uri {
        if (!file.exists() || file.length() < 32) error("录像文件无效")
        if (saveToGallery) {
            return saveVideoToGallery(file)
        }
        val tree = prefsTree()
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(context, tree)
            if (dir != null && dir.canWrite()) {
                val created = dir.createFile("video/mp4", file.name)
                    ?: error("无法在所选文件夹创建视频")
                context.contentResolver.openOutputStream(created.uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("无法写入视频")
                file.delete()
                return created.uri
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PeerLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建视频")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("无法写入视频")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PeerLink"
        )
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        file.delete()
        return Uri.fromFile(dest)
    }

    private fun saveVideoToGallery(file: File): Uri {
        val name = "VID_${stamp()}.mp4"
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Video.Media.DATE_TAKEN, now)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("无法写入相册")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("无法写入视频")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera"
        )
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        file.delete()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, dest.absolutePath)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, now)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(dest)
    }

    private fun prefsTree(): Uri? {
        val prefs = context.getSharedPreferences("peerlink_transfer", Context.MODE_PRIVATE)
        return prefs.getString("receive_tree_uri", null)?.let(Uri::parse)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "CastCapture"

        /** Split codec-config into SPS / PPS (Annex-B or length-prefixed). */
        fun splitSpsPps(data: ByteArray): Pair<ByteArray, ByteArray>? {
            val nals = splitNals(data)
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in nals) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
            return if (sps != null && pps != null) sps to pps else null
        }

        fun annexBToLengthPrefixed(data: ByteArray): ByteArray? {
            val nals = splitNals(data)
            if (nals.isEmpty()) return null
            var total = 0
            for (n in nals) total += 4 + n.size
            val out = ByteArray(total)
            var o = 0
            for (n in nals) {
                val len = n.size
                out[o++] = (len ushr 24).toByte()
                out[o++] = (len ushr 16).toByte()
                out[o++] = (len ushr 8).toByte()
                out[o++] = len.toByte()
                System.arraycopy(n, 0, out, o, len)
                o += len
            }
            return out
        }

        private fun splitNals(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            if (hasStartCode(data)) {
                val nalStarts = ArrayList<Int>()
                var i = 0
                while (i < data.size - 3) {
                    val sc = startCodeLen(data, i)
                    if (sc > 0) {
                        nalStarts.add(i + sc)
                        i += sc
                    } else {
                        i++
                    }
                }
                val out = ArrayList<ByteArray>(nalStarts.size)
                for (idx in nalStarts.indices) {
                    val from = nalStarts[idx]
                    val to = if (idx + 1 < nalStarts.size) {
                        val next = nalStarts[idx + 1]
                        when {
                            next >= 4 && startCodeLen(data, next - 4) == 4 -> next - 4
                            next >= 3 && startCodeLen(data, next - 3) == 3 -> next - 3
                            else -> next
                        }
                    } else {
                        data.size
                    }
                    if (to > from) out.add(data.copyOfRange(from, to))
                }
                return out
            }
            // Length-prefixed AVCC
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i + 4 <= data.size) {
                val len = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
                i += 4
                if (len <= 0 || i + len > data.size) break
                out.add(data.copyOfRange(i, i + len))
                i += len
            }
            return out
        }

        private fun hasStartCode(data: ByteArray): Boolean =
            startCodeLen(data, 0) > 0

        private fun startCodeLen(data: ByteArray, i: Int): Int {
            if (i + 3 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) return 4
            if (i + 2 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) return 3
            return 0
        }
    }
}
