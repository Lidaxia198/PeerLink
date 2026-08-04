package com.peerlink.app.network

import com.peerlink.app.cast.ScreenEncoder
import com.peerlink.app.protocol.Packet
import com.peerlink.app.protocol.PacketCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState {
    Idle, Hosting, Connecting, Connected, Failed, Closed
}

class PeerSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null

    private val outbound = LinkedBlockingQueue<Outbound>(32)

    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _remoteName = MutableStateFlow<String?>(null)
    val remoteName: StateFlow<String?> = _remoteName.asStateFlow()

    private val _packets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val packets: SharedFlow<Packet> = _packets.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var attachedCloseables: List<Closeable> = emptyList()

    @Volatile
    private var expectedRoomKey: String = ""

    @Volatile
    private var hostMode: Boolean = false

    @Volatile
    private var hostDeviceName: String = ""

    @Volatile
    private var peerDone: CompletableDeferred<Unit>? = null

    /** TCP link up but HELLO/key not verified yet. */
    @Volatile
    private var peerLinkActive: Boolean = false

    /** True only after mutual HELLO + room key check passed. */
    @Volatile
    private var peerAuthed: Boolean = false

    val wasPeerAuthed: Boolean get() = peerAuthed

    /**
     * Host keeps the room alive: after a guest leaves, go back to accepting
     * the next client instead of tearing the room down.
     */
    fun startHost(port: Int = NsdDiscovery.DEFAULT_PORT, deviceName: String, roomKey: String) {
        if (!running.compareAndSet(false, true)) return
        hostMode = true
        hostDeviceName = deviceName
        expectedRoomKey = roomKey
        _error.value = null
        _state.value = ConnectionState.Hosting
        scope.launch {
            try {
                val server = ServerSocket().also { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(port))
                    serverSocket = ss
                }
                while (running.get() && hostMode) {
                    _remoteName.value = null
                    _error.value = null
                    _state.value = ConnectionState.Hosting
                    val client = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    if (!running.get() || !hostMode) {
                        runCatching { client.close() }
                        break
                    }
                    val done = CompletableDeferred<Unit>()
                    peerDone = done
                    try {
                        bindClient(client, hostDeviceName, role = "host", roomKey = expectedRoomKey)
                        done.await()
                    } catch (_: Throwable) {
                        Unit
                    } finally {
                        val hadAuth = peerAuthed
                        stopPeerJobs()
                        cleanupClientOnly()
                        peerDone = null
                        peerLinkActive = false
                        peerAuthed = false
                        // Only treat as "guest left" when they had fully joined.
                        if (hadAuth && running.get() && hostMode) {
                            _state.value = ConnectionState.Hosting
                        } else if (running.get() && hostMode) {
                            _state.value = ConnectionState.Hosting
                            _error.value = null
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    _error.value = t.message ?: "Host failed"
                    _state.value = ConnectionState.Failed
                }
            } finally {
                cleanupAll()
                if (_state.value == ConnectionState.Hosting || _state.value == ConnectionState.Connected) {
                    _state.value = ConnectionState.Closed
                }
                running.set(false)
                hostMode = false
            }
        }
    }

    fun connect(host: String, port: Int, deviceName: String, roomKey: String) {
        if (!running.compareAndSet(false, true)) return
        hostMode = false
        expectedRoomKey = roomKey
        _error.value = null
        _state.value = ConnectionState.Connecting
        scope.launch {
            try {
                val client = Socket().also {
                    it.tcpNoDelay = true
                    it.keepAlive = true
                    it.connect(InetSocketAddress(host, port), 5_000)
                    it.sendBufferSize = 512 * 1024
                    it.receiveBufferSize = 512 * 1024
                }
                val done = CompletableDeferred<Unit>()
                peerDone = done
                bindClient(client, deviceName, role = "client", roomKey = roomKey)
                done.await()
                when {
                    _state.value == ConnectionState.Failed -> Unit
                    peerAuthed && _state.value == ConnectionState.Connected -> {
                        _state.value = ConnectionState.Closed
                    }
                    !peerAuthed -> {
                        if (_error.value.isNullOrBlank()) {
                            _error.value = "房间密钥错误"
                        }
                        _state.value = ConnectionState.Failed
                    }
                }
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                _error.value = when {
                    msg.contains("密钥") -> msg
                    msg.contains("ECONNREFUSED", true) ||
                        msg.contains("Connection refused", true) ||
                        msg.contains("ENETUNREACH", true) ||
                        msg.contains("EHOSTUNREACH", true) ||
                        msg.contains("timed out", true) ||
                        msg.contains("SocketTimeout", true) ||
                        msg.contains("failed to connect", true) ||
                        msg.contains("Unable to resolve", true) ->
                        "找不到该主机，请确认 IP 正确且对方已创建房间"
                    else -> t.message ?: "连接失败"
                }
                _state.value = ConnectionState.Failed
            } finally {
                stopPeerJobs()
                cleanupAll()
                running.set(false)
                peerDone = null
            }
        }
    }

    fun attachStreams(
        inputStream: InputStream,
        outputStream: OutputStream,
        deviceName: String,
        roomKey: String,
        closeables: List<Closeable> = emptyList()
    ) {
        if (!running.compareAndSet(false, true)) return
        hostMode = false
        expectedRoomKey = roomKey
        _error.value = null
        _state.value = ConnectionState.Connecting
        scope.launch {
            try {
                attachedCloseables = closeables
                input = BufferedInputStream(inputStream, 64 * 1024)
                output = BufferedOutputStream(outputStream, 32 * 1024)
                val done = CompletableDeferred<Unit>()
                peerDone = done
                outbound.clear()
                peerAuthed = false
                peerLinkActive = true
                enqueueReliable(
                    PacketCodec.TYPE_HELLO,
                    PacketCodec.encodeHello(deviceName, "peer", roomKey)
                )
                startWriter()
                startReader()
                startHeartbeat()
                done.await()
                when {
                    _state.value == ConnectionState.Failed -> Unit
                    peerAuthed && _state.value == ConnectionState.Connected -> {
                        _state.value = ConnectionState.Closed
                    }
                    !peerAuthed -> {
                        if (_error.value.isNullOrBlank()) {
                            _error.value = "房间密钥错误"
                        }
                        _state.value = ConnectionState.Failed
                    }
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Attach failed"
                _state.value = ConnectionState.Failed
            } finally {
                stopPeerJobs()
                cleanupAll()
                running.set(false)
                peerDone = null
                peerLinkActive = false
                peerAuthed = false
            }
        }
    }

    private fun bindClient(client: Socket, deviceName: String, role: String, roomKey: String) {
        client.tcpNoDelay = true
        client.keepAlive = true
        runCatching { client.sendBufferSize = 512 * 1024 }
        runCatching { client.receiveBufferSize = 512 * 1024 }
        socket = client
        input = BufferedInputStream(client.getInputStream(), 64 * 1024)
        // Smaller buffer + per-frame flush keeps cast latency low on LAN.
        output = BufferedOutputStream(client.getOutputStream(), 32 * 1024)
        outbound.clear()
        peerAuthed = false
        peerLinkActive = true
        enqueueReliable(
            PacketCodec.TYPE_HELLO,
            PacketCodec.encodeHello(deviceName, role, roomKey)
        )
        // Stay Hosting / Connecting until HELLO + key checks pass.
        if (!hostMode) {
            _state.value = ConnectionState.Connecting
        }
        startWriter()
        startReader()
        startHeartbeat()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running.get() && peerLinkActive) {
                try {
                    kotlinx.coroutines.delay(2_500)
                } catch (_: Throwable) {
                    break
                }
                if (peerAuthed && peerLinkActive && running.get()) {
                    outbound.offer(
                        Outbound(
                            type = PacketCodec.TYPE_HEARTBEAT,
                            payload = ByteArray(0),
                            reliable = false,
                            flags = 0
                        )
                    )
                }
            }
        }
    }

    private fun startWriter() {
        writerJob?.cancel()
        writerJob = scope.launch {
            while (isActive && running.get() && peerLinkActive) {
                val item = try {
                    outbound.poll(80, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                if (item.type == 0.toByte() && item.payload.isEmpty()) continue
                val out = output ?: continue
                try {
                    val isVideo = item.type == PacketCodec.TYPE_VIDEO
                    // Flush every video frame — large BufferedOutputStream otherwise adds tens of ms.
                    val shouldFlush = isVideo ||
                        item.flags == ScreenEncoder.FLAG_CONFIG ||
                        item.flags == ScreenEncoder.FLAG_KEY ||
                        outbound.isEmpty()
                    PacketCodec.write(out, item.type, item.payload, flush = shouldFlush)
                } catch (_: Throwable) {
                    if (item.reliable) {
                        signalPeerGone()
                        break
                    }
                }
            }
        }
    }

    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                while (isActive && running.get() && peerLinkActive) {
                    val packet = PacketCodec.read(input!!) ?: break
                    when (packet.type) {
                        PacketCodec.TYPE_HELLO -> {
                            val (name, _, remoteKey) = PacketCodec.decodeHello(packet.payload)
                            if (remoteKey != expectedRoomKey) {
                                // Only the host notifies the guest. Guest must NOT send
                                // auth_fail back — that previously killed the host room.
                                // Host must not set Failed/error here or the room dies / shows
                                // false "对方已离开".
                                if (hostMode) {
                                    runCatching {
                                        val out = output
                                        if (out != null) {
                                            PacketCodec.write(
                                                out,
                                                PacketCodec.TYPE_CONTROL,
                                                PacketCodec.encodeControl("auth_fail"),
                                                flush = true
                                            )
                                        }
                                    }
                                } else {
                                    _error.value = "房间密钥错误"
                                    _state.value = ConnectionState.Failed
                                }
                                peerLinkActive = false
                                signalPeerGone()
                                break
                            }
                            _error.value = null
                            _remoteName.value = name
                            peerAuthed = true
                            _state.value = ConnectionState.Connected
                        }
                        PacketCodec.TYPE_CONTROL -> {
                            val action = PacketCodec.decodeControl(packet.payload)
                            if (action == "auth_fail") {
                                // Guests only — host ignores so a bad join can't close the room.
                                if (!hostMode) {
                                    _error.value = "房间密钥错误"
                                    _state.value = ConnectionState.Failed
                                    peerLinkActive = false
                                    signalPeerGone()
                                    break
                                }
                            } else if (peerAuthed) {
                                _packets.emit(packet)
                            }
                        }
                        PacketCodec.TYPE_VIDEO -> {
                            if (peerAuthed) _packets.tryEmit(packet)
                        }
                        else -> {
                            if (peerAuthed || packet.type == PacketCodec.TYPE_HEARTBEAT) {
                                _packets.emit(packet)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // peer closed / local shutdown
            } finally {
                peerLinkActive = false
                signalPeerGone()
            }
        }
    }

    private fun signalPeerGone() {
        peerDone?.complete(Unit)
    }

    fun send(type: Byte, payload: ByteArray = ByteArray(0)) {
        enqueueReliable(type, payload)
    }

    fun sendVideo(flags: Byte, data: ByteArray) {
        if (!running.get() || _state.value != ConnectionState.Connected) return
        val critical = flags == ScreenEncoder.FLAG_CONFIG || flags == ScreenEncoder.FLAG_KEY
        // Drop only stale P-frames — never discard SPS/PPS or IDR (first paint depends on them).
        if (!critical) {
            while (outbound.size > 1) {
                val it = outbound.iterator()
                var removed = false
                while (it.hasNext()) {
                    val next = it.next()
                    if (!next.reliable && next.flags == ScreenEncoder.FLAG_FRAME) {
                        it.remove()
                        removed = true
                        break
                    }
                }
                if (!removed) break
            }
        }
        val payload = PacketCodec.encodeVideo(flags, data)
        val item = Outbound(
            type = PacketCodec.TYPE_VIDEO,
            payload = payload,
            reliable = critical,
            flags = flags
        )
        if (!outbound.offer(item)) {
            if (critical) {
                // Make room for SPS/IDR even if the queue is jammed with P-frames.
                val it = outbound.iterator()
                while (it.hasNext()) {
                    val next = it.next()
                    if (!next.reliable && next.flags == ScreenEncoder.FLAG_FRAME) {
                        it.remove()
                        break
                    }
                }
                outbound.offer(item)
            }
        }
    }

    fun sendControl(action: String) {
        enqueueReliable(PacketCodec.TYPE_CONTROL, PacketCodec.encodeControl(action))
    }

    private fun enqueueReliable(type: Byte, payload: ByteArray) {
        outbound.offer(
            Outbound(type = type, payload = payload, reliable = true, flags = 0)
        )
    }

    fun close() {
        hostMode = false
        running.set(false)
        outbound.clear()
        outbound.offer(Outbound(type = 0, payload = ByteArray(0), reliable = false, flags = 0))
        signalPeerGone()
        stopPeerJobs()
        cleanupAll()
        if (_state.value != ConnectionState.Failed) {
            _state.value = ConnectionState.Closed
        }
        scope.cancel()
    }

    private fun stopPeerJobs() {
        heartbeatJob?.cancel()
        readerJob?.cancel()
        writerJob?.cancel()
        heartbeatJob = null
        readerJob = null
        writerJob = null
    }

    private fun cleanupClientOnly() {
        runCatching { socket?.close() }
        runCatching { input?.close() }
        runCatching { output?.close() }
        attachedCloseables.forEach { runCatching { it.close() } }
        socket = null
        input = null
        output = null
        attachedCloseables = emptyList()
        outbound.clear()
    }

    private fun cleanupAll() {
        cleanupClientOnly()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private data class Outbound(
        val type: Byte,
        val payload: ByteArray,
        val reliable: Boolean,
        val flags: Byte
    )
}
