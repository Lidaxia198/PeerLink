package com.peerlink.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.peerlink.app.network.PeerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID

class BluetoothLink(context: Context) {
    private val adapter: BluetoothAdapter? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }.getOrNull()

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "PeerLinkBt"
    }

    fun isAvailable(): Boolean = adapter != null

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        val bt = adapter ?: return emptyList()
        return bt.bondedDevices?.toList().orEmpty()
    }

    @SuppressLint("MissingPermission")
    suspend fun host(session: PeerSession, deviceName: String, roomKey: String) = withContext(Dispatchers.IO) {
        val bt = adapter ?: error("Bluetooth unavailable")
        if (!bt.isEnabled) error("请先打开蓝牙")
        var server: BluetoothServerSocket? = null
        var socket: BluetoothSocket? = null
        try {
            server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            socket = server.accept()
            server.close()
            session.attachStreams(
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                deviceName = deviceName,
                roomKey = roomKey,
                closeables = listOf(socket as Closeable)
            )
        } catch (t: Throwable) {
            runCatching { socket?.close() }
            runCatching { server?.close() }
            throw t
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, session: PeerSession, deviceName: String, roomKey: String) =
        withContext(Dispatchers.IO) {
            val bt = adapter ?: error("Bluetooth unavailable")
            if (!bt.isEnabled) error("请先打开蓝牙")
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                bt.cancelDiscovery()
                socket.connect()
                session.attachStreams(
                    inputStream = socket.inputStream,
                    outputStream = socket.outputStream,
                    deviceName = deviceName,
                    roomKey = roomKey,
                    closeables = listOf(socket as Closeable)
                )
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                throw t
            }
        }
}
