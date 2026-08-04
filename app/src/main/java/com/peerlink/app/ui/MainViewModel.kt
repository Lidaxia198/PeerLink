package com.peerlink.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerlink.app.bluetooth.BluetoothLink
import com.peerlink.app.cast.CastQuality
import com.peerlink.app.cast.RemoteShutter
import com.peerlink.app.cast.ScreenDecoder
import com.peerlink.app.network.ConnectionState
import com.peerlink.app.network.LanPeer
import com.peerlink.app.network.NsdDiscovery
import com.peerlink.app.network.PeerSession
import com.peerlink.app.network.PeerSessionHolder
import com.peerlink.app.network.localDeviceName
import com.peerlink.app.network.primaryWifiIpv4
import com.peerlink.app.protocol.PacketCodec
import com.peerlink.app.service.CastForegroundService
import com.peerlink.app.service.LinkKeepAliveService
import com.peerlink.app.transfer.FileTransferManager
import com.peerlink.app.transfer.TransferProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

enum class LinkMode { Wifi, Bluetooth }
enum class AppRole { Idle, Host, Guest }

data class UiState(
    val role: AppRole = AppRole.Idle,
    val mode: LinkMode = LinkMode.Wifi,
    val connection: ConnectionState = ConnectionState.Idle,
    val deviceName: String = "",
    val remoteName: String? = null,
    val localIp: String? = null,
    val peers: List<LanPeer> = emptyList(),
    val btDevices: List<BtDeviceUi> = emptyList(),
    val statusText: String = "选择角色开始",
    val casting: Boolean = false,
    val receivingCast: Boolean = false,
    val castQuality: CastQuality = CastQuality.Smooth,
    val castFitMode: CastFitMode = CastFitMode.Fill,
    val castMirrored: Boolean = false,
    val roomKey: String = "",
    val pipHidden: Boolean = false,
    val transfer: TransferProgress? = null,
    val recentFiles: List<TransferProgress> = emptyList(),
    val receiveFolderLabel: String = "默认：Download/PeerLink",
    val error: String? = null,
    /** Soft notice dialog (title to message). */
    val noticeTitle: String? = null,
    val noticeMessage: String? = null,
    /** Short toast overlay; auto-dismissed in UI. */
    val toastMessage: String? = null,
    /** Guest should open the room-key dialog (e.g. IP join hit a keyed room). */
    val promptRoomKey: Boolean = false
)

data class BtDeviceUi(val name: String, val address: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val discovery = NsdDiscovery(app)
    private val bluetooth = BluetoothLink(app)
    private val transferManager = FileTransferManager(app)

    private var session: PeerSession? = null
    private var advertiseJob: Job? = null
    private var discoverJob: Job? = null
    private var sessionJobs: Job? = null

    val decoder = ScreenDecoder(
        onError = { msg ->
            if (msg.contains("cancelled", ignoreCase = true) ||
                msg.contains("BufferQueue", ignoreCase = true)
            ) {
                return@ScreenDecoder
            }
            _ui.value = _ui.value.copy(error = msg)
        }
    )

    private val _ui = MutableStateFlow(
        UiState(
            deviceName = localDeviceName(),
            localIp = primaryWifiIpv4(app),
            receiveFolderLabel = transferManager.receiveFolderLabel(),
            castQuality = loadSavedQuality(app)
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        PeerSessionHolder.setQuality(_ui.value.castQuality)
        viewModelScope.launch {
            transferManager.progress.collectLatest { p ->
                _ui.value = _ui.value.copy(transfer = p)
            }
        }
        viewModelScope.launch {
            transferManager.recent.collectLatest { list ->
                _ui.value = _ui.value.copy(recentFiles = list)
            }
        }
        viewModelScope.launch {
            PeerSessionHolder.casting.collectLatest { active ->
                val cur = _ui.value
                if (cur.casting == active) return@collectLatest
                _ui.value = cur.copy(
                    casting = active,
                    statusText = when {
                        active -> "正在投屏…"
                        cur.connection == ConnectionState.Connected -> "已连接"
                        else -> cur.statusText
                    }
                )
                if (active && !RemoteShutter.isReady()) {
                    showNotice(
                        "帮拍需开无障碍",
                        "设置 → 无障碍 → PeerLink，开启后对方才能代按快门"
                    )
                }
            }
        }
        viewModelScope.launch {
            PeerSessionHolder.quality.collectLatest { q ->
                if (_ui.value.castQuality != q) {
                    _ui.value = _ui.value.copy(castQuality = q)
                }
            }
        }
    }

    /**
     * Quality can only be chosen by the casting side (or before starting cast).
     * Receiver requests used to restart the remote encoder and often killed the stream on OEM phones.
     */
    fun setCastQuality(quality: CastQuality) {
        if (_ui.value.receivingCast && !_ui.value.casting) return
        PeerSessionHolder.setQuality(quality)
        _ui.value = _ui.value.copy(castQuality = quality)
        saveQuality(getApplication(), quality)
        if (_ui.value.casting) {
            val app = getApplication<Application>()
            val intent = Intent(app, CastForegroundService::class.java).apply {
                action = CastForegroundService.ACTION_UPDATE_QUALITY
                putExtra(CastForegroundService.EXTRA_QUALITY, quality.name.lowercase())
            }
            app.startService(intent)
            showToast("已切换到${quality.label}")
        }
    }

    fun setCastFitMode(mode: CastFitMode) {
        _ui.value = _ui.value.copy(castFitMode = mode)
    }

    fun setCastMirrored(mirrored: Boolean) {
        _ui.value = _ui.value.copy(castMirrored = mirrored)
    }

    /** Ask the casting phone to press its camera shutter (photo or video). */
    fun pressRemoteShutter() {
        if (!_ui.value.receivingCast) {
            showToast("需要先接收对方投屏")
            return
        }
        PeerSessionHolder.session?.sendControl("remote_photo")
    }

    fun setPipHidden(hidden: Boolean) {
        _ui.value = _ui.value.copy(pipHidden = hidden)
    }

    fun setMode(mode: LinkMode) {
        _ui.value = _ui.value.copy(mode = mode)
        if (mode == LinkMode.Bluetooth) {
            refreshBluetoothDevices()
        }
    }

    fun refreshBluetoothDevices() {
        val list = bluetooth.pairedDevices().map {
            BtDeviceUi(name = it.name ?: it.address, address = it.address)
        }
        _ui.value = _ui.value.copy(btDevices = list)
    }

    fun showNotice(title: String, message: String) {
        _ui.value = _ui.value.copy(noticeTitle = title, noticeMessage = message, error = null)
    }

    fun showToast(message: String) {
        _ui.value = _ui.value.copy(toastMessage = message)
    }

    fun dismissToast() {
        _ui.value = _ui.value.copy(toastMessage = null)
    }

    fun dismissNotice() {
        _ui.value = _ui.value.copy(noticeTitle = null, noticeMessage = null)
    }

    fun consumePromptRoomKey() {
        _ui.value = _ui.value.copy(promptRoomKey = false)
    }

    fun setRoomKeyInput(key: String) {
        val digits = key.filter { it.isDigit() }.take(6)
        _ui.value = _ui.value.copy(roomKey = digits)
    }

    fun startAsHost(withRoomKey: Boolean = true) {
        runCatching {
            stopAll(keepUiMode = true)
            val key = if (withRoomKey) (100_000..999_999).random().toString() else ""
            val s = PeerSession()
            session = s
            PeerSessionHolder.session = s
            _ui.value = _ui.value.copy(
                role = AppRole.Host,
                roomKey = key,
                statusText = if (_ui.value.mode == LinkMode.Wifi) "等待局域网连接…" else "等待蓝牙连接…",
                localIp = primaryWifiIpv4(getApplication()),
                error = null
            )
            observeSession(s)

            if (_ui.value.mode == LinkMode.Wifi) {
                s.startHost(NsdDiscovery.DEFAULT_PORT, _ui.value.deviceName, key)
                advertiseJob = viewModelScope.launch {
                    runCatching {
                        discovery.register(
                            serviceName = _ui.value.deviceName,
                            port = NsdDiscovery.DEFAULT_PORT,
                            requiresKey = key.isNotEmpty()
                        ).collect { }
                    }.onFailure { err ->
                        _ui.value = _ui.value.copy(
                            error = "局域网广播失败：${err.message ?: "请用 IP 手动连接"}"
                        )
                    }
                }
            } else {
                viewModelScope.launch {
                    runCatching { bluetooth.host(s, _ui.value.deviceName, key) }
                        .onFailure {
                            _ui.value = _ui.value.copy(
                                error = it.message,
                                statusText = "蓝牙主机失败",
                                connection = ConnectionState.Failed
                            )
                        }
                }
            }
        }.onFailure {
            _ui.value = _ui.value.copy(
                error = it.message ?: "创建房间失败",
                statusText = "创建失败",
                connection = ConnectionState.Failed,
                role = AppRole.Idle
            )
        }
    }

    fun startAsGuest() {
        stopAll(keepUiMode = true)
        _ui.value = _ui.value.copy(
            role = AppRole.Guest,
            roomKey = "",
            statusText = if (_ui.value.mode == LinkMode.Wifi) "正在搜索设备…" else "选择已配对蓝牙设备",
            error = null,
            peers = emptyList()
        )
        if (_ui.value.mode == LinkMode.Wifi) {
            startLanDiscovery()
        } else {
            refreshBluetoothDevices()
        }
    }

    /** Restart mDNS scan (NSD can stall on some OEM Wi‑Fi stacks). */
    fun refreshLanPeers() {
        if (_ui.value.role != AppRole.Guest || _ui.value.mode != LinkMode.Wifi) return
        if (_ui.value.connection == ConnectionState.Connected) return
        _ui.value = _ui.value.copy(
            peers = emptyList(),
            statusText = "正在搜索设备…"
        )
        startLanDiscovery()
    }

    private fun startLanDiscovery() {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            // Periodically restart discovery — some routers / OEMs drop mDNS after a while.
            while (true) {
                try {
                    withTimeoutOrNull(15_000) {
                        discovery.discover().collect { peers ->
                            _ui.value = _ui.value.copy(peers = peers)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _ui.value = _ui.value.copy(
                        error = "搜索失败：${t.message ?: "请改用 IP 手动连接"}"
                    )
                }
                if (_ui.value.connection == ConnectionState.Connected) break
                if (_ui.value.role != AppRole.Guest) break
                delay(400)
            }
        }
    }

    /** Empty = skip key check; partial input is rejected. */
    private fun optionalRoomKey(): String? {
        val key = _ui.value.roomKey.trim()
        if (key.isEmpty()) return ""
        if (key.length != 6 || key.any { !it.isDigit() }) {
            _ui.value = _ui.value.copy(error = "密钥需为 6 位数字，也可留空")
            return null
        }
        return key
    }

    fun connectLanManual(host: String, port: Int = NsdDiscovery.DEFAULT_PORT, roomKey: String? = null) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            _ui.value = _ui.value.copy(error = "请输入主机 IP")
            return
        }
        if (roomKey != null) {
            _ui.value = _ui.value.copy(roomKey = roomKey.filter { it.isDigit() }.take(6))
        }
        val key = optionalRoomKey() ?: return
        startLanConnection(LanPeer(name = trimmed, host = trimmed, port = port), key)
    }

    fun connectLan(peer: LanPeer, roomKey: String? = null) {
        if (roomKey != null) {
            _ui.value = _ui.value.copy(roomKey = roomKey.filter { it.isDigit() }.take(6))
        }
        val key = optionalRoomKey() ?: return
        startLanConnection(peer, key)
    }

    private fun startLanConnection(peer: LanPeer, key: String) {
        val s = PeerSession()
        session = s
        PeerSessionHolder.session = s
        observeSession(s)
        _ui.value = _ui.value.copy(statusText = "正在连接 ${peer.name}…", error = null)
        s.connect(peer.host, peer.port, _ui.value.deviceName, key)
    }

    fun connectBluetooth(address: String, roomKey: String? = null) {
        if (roomKey != null) {
            _ui.value = _ui.value.copy(roomKey = roomKey.filter { it.isDigit() }.take(6))
        }
        val key = optionalRoomKey() ?: return
        val device = bluetooth.pairedDevices().firstOrNull { it.address == address }
            ?: run {
                _ui.value = _ui.value.copy(error = "未找到设备，请先系统配对")
                return
            }
        val s = PeerSession()
        session = s
        PeerSessionHolder.session = s
        observeSession(s)
        _ui.value = _ui.value.copy(statusText = "蓝牙连接中…", error = null)
        viewModelScope.launch {
            runCatching { bluetooth.connect(device, s, _ui.value.deviceName, key) }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        error = it.message,
                        statusText = "蓝牙连接失败",
                        connection = ConnectionState.Failed
                    )
                }
        }
    }

    private fun observeSession(s: PeerSession) {
        sessionJobs?.cancel()
        val handler = CoroutineExceptionHandler { _, t ->
            _ui.value = _ui.value.copy(error = t.message ?: "会话异常")
        }
        sessionJobs = viewModelScope.launch(handler) {
            supervisorScope {
            launch {
                var previous = ConnectionState.Idle
                var hadAuthedGuest = false
                s.state.collectLatest { st ->
                    // Keep-alive only while peer is linked (folder picker / background).
                    // Do not start on Hosting — that caused create-room crashes on OEMs.
                    if (st == ConnectionState.Connected) {
                        LinkKeepAliveService.start(getApplication())
                        hadAuthedGuest = true
                    } else {
                        LinkKeepAliveService.stop(getApplication())
                    }

                    // Only when a fully joined guest leaves — not auth reject.
                    val guestLeftRoom =
                        _ui.value.role == AppRole.Host &&
                            previous == ConnectionState.Connected &&
                            st == ConnectionState.Hosting &&
                            hadAuthedGuest

                    if (guestLeftRoom) {
                        hadAuthedGuest = false
                        if (_ui.value.casting) {
                            val app = getApplication<Application>()
                            app.startService(
                                Intent(app, CastForegroundService::class.java).apply {
                                    action = CastForegroundService.ACTION_STOP
                                }
                            )
                        }
                    }
                    if (st == ConnectionState.Hosting && previous != ConnectionState.Connected) {
                        // Auth reject / still waiting — don't treat as guest left.
                        hadAuthedGuest = false
                    }

                    val disconnectHint = when {
                        guestLeftRoom -> null
                        st == ConnectionState.Closed && _ui.value.role != AppRole.Host ->
                            "连接已断开"
                        st == ConnectionState.Failed -> _ui.value.error
                        else -> _ui.value.error
                    }

                    _ui.value = _ui.value.copy(
                        connection = st,
                        statusText = when (st) {
                            ConnectionState.Hosting ->
                                if (guestLeftRoom) "对方已离开，继续等待加入…"
                                else "等待对方加入…"
                            ConnectionState.Connecting -> "连接中…"
                            ConnectionState.Connected ->
                                if (_ui.value.casting) "正在投屏…" else "已连接"
                            ConnectionState.Failed -> "连接失败"
                            ConnectionState.Closed -> "连接已断开"
                            ConnectionState.Idle -> "空闲"
                        },
                        receivingCast = if (
                            st == ConnectionState.Closed ||
                            st == ConnectionState.Failed ||
                            st == ConnectionState.Hosting
                        ) {
                            false
                        } else {
                            _ui.value.receivingCast
                        },
                        casting = if (
                            st == ConnectionState.Closed ||
                            st == ConnectionState.Failed ||
                            guestLeftRoom
                        ) {
                            false
                        } else {
                            _ui.value.casting
                        },
                        remoteName = if (st == ConnectionState.Hosting) null else _ui.value.remoteName,
                        error = if (guestLeftRoom) null else disconnectHint,
                        noticeTitle = if (guestLeftRoom) "对方已离开" else _ui.value.noticeTitle,
                        noticeMessage = if (guestLeftRoom) {
                            "房间仍在，可继续等待下一位加入"
                        } else {
                            _ui.value.noticeMessage
                        }
                    )
                    if (st == ConnectionState.Connected) {
                        decoder.start()
                    }
                    if (st == ConnectionState.Closed && _ui.value.role == AppRole.Guest) {
                        _ui.value = _ui.value.copy(role = AppRole.Idle, error = null)
                    }
                    if (st == ConnectionState.Failed) {
                        val err = s.error.value.orEmpty().ifBlank { _ui.value.error.orEmpty() }
                        when {
                            err.contains("找不到该主机") ||
                                err.contains("Connect failed", true) -> {
                                _ui.value = _ui.value.copy(
                                    noticeTitle = "无法连接",
                                    noticeMessage = if (err.contains("找不到")) err
                                    else "请确认 IP 正确，且对方已创建房间并在同一 Wi‑Fi",
                                    error = null
                                )
                            }
                            err.contains("密钥") -> {
                                // Auto-prompt key entry (IP / BT probe with empty key).
                                _ui.value = _ui.value.copy(
                                    noticeTitle = null,
                                    noticeMessage = null,
                                    promptRoomKey = true,
                                    error = null
                                )
                            }
                        }
                    }
                    previous = st
                }
            }
            launch {
                s.remoteName.collectLatest { name ->
                    _ui.value = _ui.value.copy(remoteName = name)
                }
            }
            launch {
                s.error.collectLatest { err ->
                    if (err != null) _ui.value = _ui.value.copy(error = err)
                }
            }
            launch {
                s.packets.collect { packet ->
                    when (packet.type) {
                        PacketCodec.TYPE_VIDEO -> {
                            if (packet.payload.isEmpty()) return@collect
                            runCatching {
                                val (flags, data) = PacketCodec.decodeVideo(packet.payload)
                                if (!_ui.value.receivingCast) {
                                    _ui.value = _ui.value.copy(receivingCast = true)
                                }
                                decoder.submit(flags, data)
                            }
                        }
                        PacketCodec.TYPE_FILE_META -> {
                            val (id, size, name) = PacketCodec.decodeFileMeta(packet.payload)
                            transferManager.onFileMeta(id, size, name)
                        }
                        PacketCodec.TYPE_FILE_CHUNK -> {
                            val (id, offset, data) = PacketCodec.decodeFileChunk(packet.payload)
                            transferManager.onFileChunk(id, offset, data)
                        }
                        PacketCodec.TYPE_FILE_DONE -> {
                            transferManager.onFileDone(PacketCodec.decodeFileDone(packet.payload))
                        }
                        PacketCodec.TYPE_CONTROL -> {
                            val ctrl = PacketCodec.decodeControl(packet.payload)
                            when {
                                ctrl == "start_cast" -> {
                                    decoder.prepareForNewStream()
                                    _ui.value = _ui.value.copy(
                                        receivingCast = true,
                                        pipHidden = false,
                                        error = null
                                    )
                                }
                                ctrl == "stop_cast" -> {
                                    _ui.value = _ui.value.copy(
                                        receivingCast = false,
                                        pipHidden = false
                                    )
                                }
                                ctrl.startsWith("quality:") -> {
                                    val q = CastQuality.fromKey(ctrl.removePrefix("quality:"))
                                    PeerSessionHolder.setQuality(q)
                                    _ui.value = _ui.value.copy(castQuality = q)
                                    if (decoder.hasStreamConfig()) {
                                        decoder.prepareForNewStream()
                                    }
                                }
                                ctrl == "need_key" -> {
                                    if (_ui.value.casting) {
                                        val app = getApplication<Application>()
                                        app.startService(
                                            Intent(app, CastForegroundService::class.java).apply {
                                                action = CastForegroundService.ACTION_REQUEST_KEY
                                            }
                                        )
                                    }
                                }
                                // Receiver asks caster to press system camera shutter.
                                ctrl == "remote_photo" -> {
                                    if (_ui.value.casting) {
                                        val app = getApplication<Application>()
                                        app.startService(
                                            Intent(app, CastForegroundService::class.java).apply {
                                                action = CastForegroundService.ACTION_REMOTE_PHOTO
                                            }
                                        )
                                        showToast("对方请求快门")
                                    }
                                }
                                ctrl == "remote_photo_ok" -> showToast("已按下快门")
                                ctrl.startsWith("remote_photo_fail") -> {
                                    val msg = ctrl.substringAfter(':', "快门失败")
                                    if (_ui.value.receivingCast) {
                                        showToast(msg)
                                    } else if (_ui.value.casting) {
                                        // Accessibility / camera issues need a clearer prompt.
                                        showNotice("帮拍失败", msg)
                                    }
                                }
                                // Legacy record cmds still map to the same shutter.
                                ctrl == "remote_record_start" || ctrl == "remote_record_stop" -> {
                                    if (_ui.value.casting) {
                                        val app = getApplication<Application>()
                                        app.startService(
                                            Intent(app, CastForegroundService::class.java).apply {
                                                action = CastForegroundService.ACTION_REMOTE_PHOTO
                                            }
                                        )
                                    }
                                }
                                ctrl == "remote_record_on" || ctrl == "remote_record_off" -> showToast("已按下快门")
                                ctrl.startsWith("remote_record_fail") -> {
                                    if (_ui.value.receivingCast) {
                                        showToast(ctrl.substringAfter(':', "快门失败"))
                                    }
                                }
                            }
                        }
                        PacketCodec.TYPE_HEARTBEAT -> Unit
                    }
                }
            }
            }
        }
    }

    fun startCastIntent(): Intent {
        val mpm = getApplication<Application>()
            .getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun onCastPermissionGranted(resultCode: Int, data: Intent) {
        val app = getApplication<Application>()
        val quality = _ui.value.castQuality
        val intent = Intent(app, CastForegroundService::class.java).apply {
            action = CastForegroundService.ACTION_START
            putExtra(CastForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CastForegroundService.EXTRA_DATA, data)
            putExtra(CastForegroundService.EXTRA_QUALITY, quality.name.lowercase())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        // Actual casting flag is driven by CastForegroundService via PeerSessionHolder.
        _ui.value = _ui.value.copy(statusText = "正在申请投屏…")
    }

    fun stopCast() {
        val app = getApplication<Application>()
        val intent = Intent(app, CastForegroundService::class.java).apply {
            action = CastForegroundService.ACTION_STOP
        }
        app.startService(intent)
        session?.sendControl("stop_cast")
        _ui.value = _ui.value.copy(casting = false, statusText = "已连接")
    }

    fun sendFile(uri: Uri, name: String?) {
        val s = session ?: return
        viewModelScope.launch {
            runCatching { transferManager.sendFile(s, uri, name) }
                .onFailure {
                    _ui.value = _ui.value.copy(error = it.message)
                }
        }
    }

    fun setReceiveFolder(uri: Uri?, remember: Boolean = true) {
        transferManager.setReceiveTreeUri(uri, remember = remember)
        _ui.value = _ui.value.copy(
            receiveFolderLabel = transferManager.receiveFolderLabel(),
            error = null
        )
    }

    fun clearReceiveFolder() {
        transferManager.clearRememberedFolder()
        _ui.value = _ui.value.copy(
            receiveFolderLabel = transferManager.receiveFolderLabel()
        )
    }

    fun openReceivedFile(item: TransferProgress) {
        val uri = item.uri ?: return
        runCatching {
            val intent = transferManager.openFileIntent(uri, item.mimeType)
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            _ui.value = _ui.value.copy(error = "无法打开文件：${it.message}")
        }
    }

    fun openReceiveFolder() {
        runCatching {
            val intent = transferManager.openFolderIntent() ?: return
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            _ui.value = _ui.value.copy(error = "无法打开文件夹，请用系统文件管理查看 Download/PeerLink")
        }
    }

    fun disconnect() {
        stopAll(keepUiMode = false)
    }

    private fun stopAll(keepUiMode: Boolean) {
        advertiseJob?.cancel()
        discoverJob?.cancel()
        sessionJobs?.cancel()
        stopCast()
        decoder.stop()
        session?.close()
        session = null
        PeerSessionHolder.session = null
        PeerSessionHolder.setCasting(false)
        LinkKeepAliveService.stop(getApplication())
        val mode = _ui.value.mode
        val folderLabel = transferManager.receiveFolderLabel()
        _ui.value = if (keepUiMode) {
            UiState(
                mode = mode,
                deviceName = localDeviceName(),
                localIp = primaryWifiIpv4(getApplication()),
                statusText = "选择角色开始",
                receiveFolderLabel = folderLabel
            )
        } else {
            UiState(
                deviceName = localDeviceName(),
                localIp = primaryWifiIpv4(getApplication()),
                receiveFolderLabel = folderLabel
            )
        }
    }

    override fun onCleared() {
        stopAll(keepUiMode = false)
        super.onCleared()
    }

    companion object {
        private const val PREFS = "peerlink_cast"
        private const val KEY_QUALITY = "quality"

        private fun loadSavedQuality(app: Application): CastQuality {
            val key = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
                .getString(KEY_QUALITY, null)
            return CastQuality.fromKey(key)
        }

        private fun saveQuality(app: Application, quality: CastQuality) {
            app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUALITY, quality.name.lowercase())
                .apply()
        }
    }
}
