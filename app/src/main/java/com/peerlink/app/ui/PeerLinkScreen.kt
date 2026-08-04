package com.peerlink.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerlink.app.cast.CastQuality
import com.peerlink.app.network.ConnectionState
import com.peerlink.app.network.LanPeer
import com.peerlink.app.network.NsdDiscovery

private sealed class JoinTarget {
    data class LanIp(val host: String) : JoinTarget()
    data class LanPeerTarget(val peer: LanPeer) : JoinTarget()
    data class Bluetooth(val address: String) : JoinTarget()
}

private val Ink = Color(0xFF152033)
private val Muted = Color(0xFF5C677A)
private val Line = Color(0xFFD5DBE6)
private val Panel = Color(0xFFF3F5F9)
private val Accent = Color(0xFF3B6FD9)

@Composable
fun PeerLinkApp(viewModel: MainViewModel, onRequestPermissions: () -> Unit) {
    val state by viewModel.ui.collectAsState()

    val castLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            viewModel.onCastPermissionGranted(result.resultCode, data)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.sendFile(uri, null)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(
                context: android.content.Context,
                input: android.net.Uri?
            ): android.content.Intent {
                return super.createIntent(context, input).addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    ) { uri ->
        if (uri != null) viewModel.setReceiveFolder(uri, remember = true)
    }

    val connected = state.connection == ConnectionState.Connected
    val pickingRole = state.role == AppRole.Idle ||
        state.connection == ConnectionState.Closed
    val waitingHost = state.role == AppRole.Host &&
        (state.connection == ConnectionState.Hosting || state.connection == ConnectionState.Connecting)
    val joining = state.role == AppRole.Guest && !connected &&
        state.connection != ConnectionState.Closed

    var showCastSettings by remember { mutableStateOf(false) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var pendingJoin by remember { mutableStateOf<JoinTarget?>(null) }
    var joinKeyInput by remember { mutableStateOf("") }
    var lastJoinTarget by remember { mutableStateOf<JoinTarget?>(null) }

    // IP / BT: empty-key probe failed → open key dialog like nearby keyed rooms.
    LaunchedEffect(state.promptRoomKey) {
        if (state.promptRoomKey) {
            val target = lastJoinTarget
            viewModel.consumePromptRoomKey()
            if (target != null) {
                pendingJoin = target
                joinKeyInput = ""
            }
        }
    }

    fun startJoin(target: JoinTarget, key: String = "") {
        lastJoinTarget = target
        when (target) {
            is JoinTarget.LanIp -> viewModel.connectLanManual(target.host, roomKey = key)
            is JoinTarget.LanPeerTarget -> viewModel.connectLan(target.peer, roomKey = key)
            is JoinTarget.Bluetooth -> viewModel.connectBluetooth(target.address, roomKey = key)
        }
    }

    fun onSelectJoin(target: JoinTarget) {
        // Nearby: open rooms join immediately; keyed rooms ask for the code.
        // IP / Bluetooth: unknown; try empty key, auth fail will reopen the dialog.
        when (target) {
            is JoinTarget.LanPeerTarget -> {
                if (target.peer.requiresKey) {
                    pendingJoin = target
                    joinKeyInput = ""
                } else {
                    startJoin(target, "")
                }
            }
            is JoinTarget.LanIp, is JoinTarget.Bluetooth -> startJoin(target, "")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEEF1F6))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                text = "PeerLink",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Text(
                text = "两台手机互相投屏、传文件",
                color = Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            StatusCard(state)

            if (!state.error.isNullOrBlank()) {
                Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (pickingRole) {
                StepLabel(1, "选择连接方式")
                Spacer(modifier = Modifier.height(10.dp))
                ModeRow(
                    mode = state.mode,
                    onMode = {
                        onRequestPermissions()
                        viewModel.setMode(it)
                    }
                )

                Spacer(modifier = Modifier.height(22.dp))
                StepLabel(2, "选择你的角色")
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onRequestPermissions()
                        showCreateRoomDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("创建房间（主机）")
                }
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = {
                        onRequestPermissions()
                        viewModel.startAsGuest()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("加入房间（访客）")
                }
            }

            if (waitingHost) {
                StepLabel(2, "等待对方加入")
                Spacer(modifier = Modifier.height(10.dp))
                InfoPanel {
                    if (state.roomKey.isNotEmpty()) {
                        Text("房间密钥（告诉对方）", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            text = state.roomKey.chunked(3).joinToString(" "),
                            color = Accent,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 6.dp)
                        )
                        Text("对方加入时需填写相同密钥", color = Muted, fontSize = 12.sp)
                    } else {
                        Text("已创建房间（无密钥）", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            "对方可直接加入，无需填写密钥",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (state.mode == LinkMode.Wifi) {
                        Text(
                            "本机 IP：${state.localIp ?: "获取中…"} · 端口 ${NsdDiscovery.DEFAULT_PORT}",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            "对方请在蓝牙列表里点你的设备",
                            color = Muted,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消") }
            }

            if (joining) {
                StepLabel(2, if (state.mode == LinkMode.Wifi) "选择主机" else "选择蓝牙设备")
                Spacer(modifier = Modifier.height(10.dp))

                if (state.mode == LinkMode.Wifi) {
                    var ipInput by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("主机 IP") },
                        placeholder = { Text("例如 192.168.1.8") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val host = ipInput.trim()
                            if (host.isEmpty()) {
                                viewModel.showNotice("请输入 IP", "先填写主机的局域网 IP，再点连接")
                            } else {
                                onSelectJoin(JoinTarget.LanIp(host))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("连接") }

                    Spacer(modifier = Modifier.height(16.dp))
                    NearbyScanPanel(
                        peers = state.peers,
                        onSelect = { onSelectJoin(JoinTarget.LanPeerTarget(it)) },
                        onRefresh = { viewModel.refreshLanPeers() }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已配对设备", fontWeight = FontWeight.SemiBold, color = Ink)
                        OutlinedButton(onClick = { viewModel.refreshBluetoothDevices() }) {
                            Text("刷新")
                        }
                    }
                    Text(
                        "无密钥直接进入；若房间有密钥，失败后可再填写",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    if (state.btDevices.isEmpty()) {
                        Text("请先在系统设置里完成蓝牙配对", color = Muted)
                    } else {
                        state.btDevices.forEach { d ->
                            SimpleRow(d.name, d.address) {
                                onSelectJoin(JoinTarget.Bluetooth(d.address))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("返回") }
            }

            if (connected) {
                StepLabel(3, "已连接，可以开始")
                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "投屏") {
                    Text(
                        text = when {
                            state.casting -> "你正在把本机画面发给对方"
                            state.receivingCast -> "正在接收对方画面（小窗可拖动，减号收起）"
                            else -> "点开始后先选画质，再授权投屏"
                        },
                        color = Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Button(
                        onClick = {
                            if (state.casting) viewModel.stopCast()
                            else showCastSettings = true
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.casting) Color(0xFFB3261E) else Accent
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Cast, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.casting) "停止投屏" else "开始投屏")
                    }
                    if (state.casting) {
                        Text(
                            "当前：${state.castQuality.label} · ${state.castQuality.hint}",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityChip("流畅", state.castQuality == CastQuality.Smooth) {
                                viewModel.setCastQuality(CastQuality.Smooth)
                            }
                            QualityChip("均衡", state.castQuality == CastQuality.Balanced) {
                                viewModel.setCastQuality(CastQuality.Balanced)
                            }
                            QualityChip("高清", state.castQuality == CastQuality.Hd) {
                                viewModel.setCastQuality(CastQuality.Hd)
                            }
                        }
                    }
                }

                if (state.receivingCast) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionCard(title = "画面显示") {
                        Text(
                            "填充更接近原比例铺满 · 小窗可拖动，左右下角可缩放，缩到很小会收起",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityChip("填充", state.castFitMode == CastFitMode.Fill) {
                                viewModel.setCastFitMode(CastFitMode.Fill)
                            }
                            QualityChip("适应", state.castFitMode == CastFitMode.Fit) {
                                viewModel.setCastFitMode(CastFitMode.Fit)
                            }
                            QualityChip("拉伸", state.castFitMode == CastFitMode.Original) {
                                viewModel.setCastFitMode(CastFitMode.Original)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityChip("镜像", state.castMirrored) {
                                viewModel.setCastMirrored(!state.castMirrored)
                            }
                            QualityChip("快门", selected = false) {
                                viewModel.pressRemoteShutter()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                SectionCard(title = "文件") {
                    FilledTonalButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发送文件")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("选择并记住文件夹") }
                        if (state.receiveFolderLabel.startsWith("已记住")) {
                            OutlinedButton(
                                onClick = { viewModel.clearReceiveFolder() },
                                modifier = Modifier.weight(0.55f)
                            ) { Text("清除") }
                        }
                    }
                    Text(
                        text = "当前：${state.receiveFolderLabel}",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("断开连接") }

                state.transfer?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    TransferBlock(
                        t = it,
                        onOpen = { viewModel.openReceivedFile(it) },
                        onOpenFolder = { viewModel.openReceiveFolder() }
                    )
                }

                if (state.recentFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("最近接收", fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.recentFiles.filter { !it.sending && it.done }.forEach { item ->
                        RecentFileRow(item) { viewModel.openReceivedFile(item) }
                    }
                }
            }
        }

        if (showCreateRoomDialog) {
            AppDialog(
                title = "创建房间",
                message = "可生成 6 位密钥保护房间，或直接创建、对方无需密钥即可加入。",
                onDismiss = { showCreateRoomDialog = false },
                primaryLabel = "使用密钥创建",
                onPrimary = {
                    showCreateRoomDialog = false
                    viewModel.startAsHost(withRoomKey = true)
                },
                secondaryLabel = "直接创建",
                onSecondary = {
                    showCreateRoomDialog = false
                    viewModel.startAsHost(withRoomKey = false)
                },
                tertiaryLabel = "取消"
            )
        }

        pendingJoin?.let { target ->
            AppDialog(
                title = "进入房间",
                message = when (target) {
                    is JoinTarget.LanIp -> "连接 ${target.host}"
                    is JoinTarget.LanPeerTarget -> "连接 ${target.peer.name}"
                    is JoinTarget.Bluetooth -> "连接蓝牙设备"
                } + "\n请填写 6 位房间密钥后进入。",
                onDismiss = {
                    pendingJoin = null
                    joinKeyInput = ""
                },
                primaryLabel = "进入",
                onPrimary = {
                    startJoin(target, joinKeyInput)
                    pendingJoin = null
                    joinKeyInput = ""
                },
                secondaryLabel = "取消",
                onSecondary = {
                    pendingJoin = null
                    joinKeyInput = ""
                },
                extraContent = {
                    OutlinedTextField(
                        value = joinKeyInput,
                        onValueChange = { joinKeyInput = it.filter { c -> c.isDigit() }.take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("房间密钥") },
                        placeholder = { Text("6 位数字") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            )
        }

        if (!state.noticeTitle.isNullOrBlank()) {
            val needKey = state.noticeTitle?.contains("密钥") == true
            AppDialog(
                title = state.noticeTitle.orEmpty(),
                message = state.noticeMessage.orEmpty(),
                onDismiss = { viewModel.dismissNotice() },
                primaryLabel = if (needKey) "填写密钥" else "知道了",
                onPrimary = {
                    viewModel.dismissNotice()
                    if (needKey) {
                        pendingJoin = lastJoinTarget
                        joinKeyInput = ""
                    }
                },
                secondaryLabel = if (needKey) "取消" else null,
                onSecondary = if (needKey) ({ viewModel.dismissNotice() }) else null
            )
        }

        if (showCastSettings) {
            AppDialog(
                title = "投屏设置",
                message = "先选画质再授权。帮拍跟手建议「流畅」。",
                onDismiss = { showCastSettings = false },
                primaryLabel = "开始投屏",
                onPrimary = {
                    showCastSettings = false
                    castLauncher.launch(viewModel.startCastIntent())
                },
                secondaryLabel = "取消",
                onSecondary = { showCastSettings = false },
                extraContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CastQuality.entries.forEach { q ->
                            QualityOptionRow(
                                quality = q,
                                selected = state.castQuality == q
                            ) { viewModel.setCastQuality(q) }
                        }
                    }
                }
            )
        }

        if (state.receivingCast) {
            FloatingCastPip(
                decoder = viewModel.decoder,
                receiving = true,
                fitMode = state.castFitMode,
                mirrored = state.castMirrored,
                onToggleMirror = { viewModel.setCastMirrored(!state.castMirrored) },
                onPressShutter = { viewModel.pressRemoteShutter() }
            )
        }

        val toast = state.toastMessage
        if (!toast.isNullOrBlank()) {
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(1400)
                viewModel.dismissToast()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = toast,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC1E2430))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val (title, detail, color) = when {
        state.connection == ConnectionState.Connected -> Triple(
            "已连接",
            buildString {
                append(if (state.role == AppRole.Host) "你是主机" else "你是访客")
                state.remoteName?.let { append(" · 对方 $it") }
                if (state.casting) append(" · 投屏中")
                if (state.receivingCast) append(" · 收屏中")
            },
            Accent
        )
        state.connection == ConnectionState.Hosting -> Triple(
            "等待加入",
            "房间已创建，等对方连接",
            Color(0xFFC47F17)
        )
        state.connection == ConnectionState.Connecting -> Triple(
            "连接中",
            state.statusText,
            Color(0xFFC47F17)
        )
        state.connection == ConnectionState.Failed -> Triple(
            "连接失败",
            state.statusText,
            Color(0xFFB3261E)
        )
        state.connection == ConnectionState.Closed -> Triple(
            "已断开",
            "可以重新创建或加入房间",
            Muted
        )
        state.role == AppRole.Guest -> Triple(
            "加入中",
            if (state.mode == LinkMode.Wifi) "搜索或输入主机 IP" else "选择已配对设备",
            Color(0xFFC47F17)
        )
        else -> Triple(
            "未连接",
            "先选 Wi‑Fi 或蓝牙，再选主机/访客",
            Muted
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 17.sp)
            Text(detail, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            text = if (state.mode == LinkMode.Wifi) "Wi‑Fi" else "蓝牙",
            color = Muted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun StepLabel(step: Int, title: String) {
    Text(
        text = "$step. $title",
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        fontSize = 16.sp
    )
}

@Composable
private fun ModeRow(mode: LinkMode, onMode: (LinkMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeButton(
            selected = mode == LinkMode.Wifi,
            icon = Icons.Outlined.Wifi,
            label = "Wi‑Fi",
            hint = "推荐投屏",
            modifier = Modifier.weight(1f)
        ) { onMode(LinkMode.Wifi) }
        ModeButton(
            selected = mode == LinkMode.Bluetooth,
            icon = Icons.Outlined.Bluetooth,
            label = "蓝牙",
            hint = "适合小文件",
            modifier = Modifier.weight(1f)
        ) { onMode(LinkMode.Bluetooth) }
    }
}

@Composable
private fun ModeButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else Color.White)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Icon(icon, null, tint = if (selected) Accent else Muted)
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(hint, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun NearbyScanPanel(
    peers: List<LanPeer>,
    onSelect: (LanPeer) -> Unit,
    onRefresh: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "scan")
    // 2s cycle: icon visible+spinning ~0.9s, then hidden ~1.1s (feels like live refresh).
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val iconVisible = phase < 0.45f
    val spin = if (iconVisible) (phase / 0.45f) * 360f else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("附近设备", fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                if (peers.isEmpty()) " · 扫描中" else " · 已发现 ${peers.size} 台",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (iconVisible) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = Accent.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(spin)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新")
            }
        }

        if (peers.isEmpty()) {
            Text(
                "请确认与主机同一 Wi‑Fi，或点刷新；也可上方输入 IP 连接",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            peers.forEach { peer ->
                SimpleRow(
                    title = peer.name,
                    subtitle = peer.host + if (peer.requiresKey) " · 需密钥" else ""
                ) { onSelect(peer) }
            }
        }
    }
}

@Composable
private fun AppDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    extraContent: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                Text(
                    message,
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (extraContent != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    extraContent()
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(primaryLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = onSecondary,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(secondaryLabel) }
                }
                if (tertiaryLabel != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tertiaryLabel, color = Muted) }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun InfoPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
    }
}

@Composable
private fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TransferBlock(
    t: com.peerlink.app.transfer.TransferProgress,
    onOpen: () -> Unit,
    onOpenFolder: () -> Unit
) {
    val ratio = if (t.total > 0) (t.transferred.toFloat() / t.total.toFloat()).coerceIn(0f, 1f) else 0f
    InfoPanel {
        Text(
            (if (t.sending) "发送中" else "接收中") + " · ${t.fileName}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Ink
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (t.done) "已完成" else "${t.transferred / 1024} KB / ${maxOf(t.total, 1) / 1024} KB",
            color = Muted,
            fontSize = 13.sp
        )
        t.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (t.done && !t.sending && t.uri != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("打开查看") }
                OutlinedButton(onClick = onOpenFolder) { Text("打开目录") }
            }
        }
    }
}

@Composable
private fun QualityOptionRow(
    quality: CastQuality,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Panel)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                quality.label,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                quality.hint,
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Accent else Line, CircleShape)
                .background(if (selected) Accent else Color.Transparent)
        )
    }
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> Panel.copy(alpha = 0.7f)
        selected -> Accent
        else -> Panel
    }
    val fg = when {
        !enabled -> Muted
        selected -> Color.White
        else -> Ink
    }
    Text(
        text = label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, if (selected && enabled) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun RecentFileRow(
    item: com.peerlink.app.transfer.TransferProgress,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
            Text(
                "${maxOf(item.total, 1) / 1024} KB",
                color = Muted,
                fontSize = 12.sp
            )
        }
        Text("打开", color = Accent, fontWeight = FontWeight.SemiBold)
    }
}
