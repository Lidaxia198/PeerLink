package com.peerlink.app.transfer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.peerlink.app.network.PeerSession
import com.peerlink.app.protocol.PacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TransferProgress(
    val transferId: Int,
    val fileName: String,
    val total: Long,
    val transferred: Long,
    val sending: Boolean,
    val done: Boolean = false,
    val error: String? = null,
    val uri: Uri? = null,
    val mimeType: String = "application/octet-stream"
)

class FileTransferManager(private val context: Context) {
    private val nextId = AtomicInteger(1)
    private val incoming = ConcurrentHashMap<Int, IncomingFile>()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("peerlink_transfer", Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _recent = MutableStateFlow<List<TransferProgress>>(emptyList())
    val recent: StateFlow<List<TransferProgress>> = _recent.asStateFlow()

    fun receiveFolderLabel(): String {
        val tree = prefs.getString(KEY_TREE_URI, null) ?: return "默认：Download/PeerLink"
        val name = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(tree))?.name
        }.getOrNull()
        val remembered = prefs.getBoolean(KEY_REMEMBER, true)
        return when {
            !name.isNullOrBlank() && remembered -> "已记住：$name"
            !name.isNullOrBlank() -> name
            remembered -> "已记住：自定义文件夹"
            else -> "自定义文件夹"
        }
    }

    fun hasRememberedFolder(): Boolean =
        !prefs.getString(KEY_TREE_URI, null).isNullOrBlank()

    fun isRememberFolder(): Boolean = prefs.getBoolean(KEY_REMEMBER, true)

    fun setRememberFolder(remember: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER, remember).apply()
        if (!remember) {
            // Keep current session path in memory only until cleared — still persist URI
            // while connected; user can clear explicitly.
        }
    }

    fun setReceiveTreeUri(uri: Uri?, remember: Boolean = true) {
        if (uri == null) {
            releasePersistedTree()
            prefs.edit().remove(KEY_TREE_URI).putBoolean(KEY_REMEMBER, true).apply()
            return
        }
        val old = prefs.getString(KEY_TREE_URI, null)
        if (old != null && old != uri.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun clearRememberedFolder() {
        releasePersistedTree()
        prefs.edit().remove(KEY_TREE_URI).putBoolean(KEY_REMEMBER, true).apply()
    }

    private fun releasePersistedTree() {
        val old = prefs.getString(KEY_TREE_URI, null) ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun getReceiveTreeUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    suspend fun sendFile(session: PeerSession, uri: Uri, displayName: String?) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName
            ?: resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
            ?: "file.bin"

        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val transferId = nextId.getAndIncrement()
        val mime = guessMime(name)
        session.send(
            PacketCodec.TYPE_FILE_META,
            PacketCodec.encodeFileMeta(name, size.coerceAtLeast(0), transferId)
        )

        var sent = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                session.send(
                    PacketCodec.TYPE_FILE_CHUNK,
                    PacketCodec.encodeFileChunk(transferId, sent, chunk)
                )
                sent += read
                _progress.value = TransferProgress(
                    transferId = transferId,
                    fileName = name,
                    total = size.coerceAtLeast(sent),
                    transferred = sent,
                    sending = true,
                    mimeType = mime
                )
            }
        }
        session.send(PacketCodec.TYPE_FILE_DONE, PacketCodec.encodeFileDone(transferId))
        val done = TransferProgress(
            transferId = transferId,
            fileName = name,
            total = size.coerceAtLeast(sent),
            transferred = sent,
            sending = true,
            done = true,
            uri = uri,
            mimeType = mime
        )
        _progress.value = done
        pushRecent(done)
    }

    fun onFileMeta(transferId: Int, fileSize: Long, fileName: String) {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val mime = guessMime(safeName)
        val target = createReceiveFile(safeName, mime)
        incoming[transferId] = IncomingFile(safeName, fileSize, target.first, target.second, mime)
        _progress.value = TransferProgress(
            transferId = transferId,
            fileName = safeName,
            total = fileSize,
            transferred = 0,
            sending = false,
            uri = target.second,
            mimeType = mime
        )
    }

    fun onFileChunk(transferId: Int, offset: Long, data: ByteArray) {
        val file = incoming[transferId] ?: return
        runCatching {
            file.output.write(data)
            file.received += data.size
            _progress.value = TransferProgress(
                transferId = transferId,
                fileName = file.name,
                total = file.total.coerceAtLeast(file.received),
                transferred = file.received,
                sending = false,
                uri = file.uri,
                mimeType = file.mimeType
            )
        }.onFailure {
            _progress.value = TransferProgress(
                transferId = transferId,
                fileName = file.name,
                total = file.total,
                transferred = file.received,
                sending = false,
                error = it.message,
                uri = file.uri,
                mimeType = file.mimeType
            )
        }
    }

    fun onFileDone(transferId: Int) {
        val file = incoming.remove(transferId) ?: return
        runCatching {
            file.output.flush()
            file.output.close()
            file.uri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    uri.authority?.contains("media", ignoreCase = true) == true
                ) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            }
        }
        val done = TransferProgress(
            transferId = transferId,
            fileName = file.name,
            total = file.total.coerceAtLeast(file.received),
            transferred = file.received,
            sending = false,
            done = true,
            uri = file.uri,
            mimeType = file.mimeType
        )
        _progress.value = done
        pushRecent(done)
    }

    fun openFileIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openFolderIntent(): Intent? {
        val tree = getReceiveTreeUri()
        return if (tree != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FPeerLink"),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun pushRecent(item: TransferProgress) {
        val next = (listOf(item) + _recent.value.filter { it.transferId != item.transferId }).take(8)
        _recent.value = next
    }

    private fun createReceiveFile(name: String, mime: String): Pair<java.io.OutputStream, Uri?> {
        val tree = getReceiveTreeUri()
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(context, tree)
            if (dir != null && dir.canWrite()) {
                val existing = dir.findFile(name)
                existing?.delete()
                val created = dir.createFile(mime, name)
                    ?: error("无法在所选文件夹创建文件")
                val out = context.contentResolver.openOutputStream(created.uri)
                    ?: error("无法写入所选文件夹")
                return out to created.uri
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PeerLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download entry")
            val out = context.contentResolver.openOutputStream(uri) ?: error("Cannot open output")
            return out to uri
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PeerLink"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        return FileOutputStream(file) to Uri.fromFile(file)
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "apk" -> "application/vnd.android.package-archive"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
    }

    private data class IncomingFile(
        val name: String,
        val total: Long,
        val output: java.io.OutputStream,
        val uri: Uri?,
        val mimeType: String,
        var received: Long = 0
    )

    companion object {
        private const val KEY_TREE_URI = "receive_tree_uri"
        private const val KEY_REMEMBER = "remember_receive_tree"
    }
}
