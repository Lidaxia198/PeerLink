package com.peerlink.app.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed framing protocol shared by LAN TCP and Bluetooth RFCOMM.
 *
 * Header (9 bytes):
 *  magic(4) = 'PLNK'
 *  type(1)
 *  length(4, big-endian)
 * Payload: [length] bytes
 */
object PacketCodec {
    const val MAGIC = 0x504C4E4B // 'PLNK'
    const val HEADER_SIZE = 9

    const val TYPE_HELLO: Byte = 1
    const val TYPE_VIDEO: Byte = 2
    const val TYPE_FILE_META: Byte = 3
    const val TYPE_FILE_CHUNK: Byte = 4
    const val TYPE_FILE_DONE: Byte = 5
    const val TYPE_CONTROL: Byte = 6
    const val TYPE_HEARTBEAT: Byte = 7

    fun write(out: OutputStream, type: Byte, payload: ByteArray = ByteArray(0), flush: Boolean = true) {
        val dos = DataOutputStream(out)
        synchronized(out) {
            dos.writeInt(MAGIC)
            dos.writeByte(type.toInt())
            dos.writeInt(payload.size)
            if (payload.isNotEmpty()) {
                dos.write(payload)
            }
            if (flush) {
                dos.flush()
            }
        }
    }

    fun read(input: InputStream): Packet? {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != MAGIC) return null
        val type = dis.readByte()
        val length = dis.readInt()
        require(length in 0..32 * 1024 * 1024) { "Invalid packet length: $length" }
        val payload = ByteArray(length)
        dis.readFully(payload)
        return Packet(type, payload)
    }

    fun encodeHello(deviceName: String, role: String, roomKey: String): ByteArray {
        val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val roleBytes = role.toByteArray(StandardCharsets.UTF_8)
        val keyBytes = roomKey.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + nameBytes.size + 4 + roleBytes.size + 4 + keyBytes.size)
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        buf.putInt(roleBytes.size)
        buf.put(roleBytes)
        buf.putInt(keyBytes.size)
        buf.put(keyBytes)
        return buf.array()
    }

    /** Returns name, role, roomKey (empty if peer uses an older handshake). */
    fun decodeHello(payload: ByteArray): Triple<String, String, String> {
        val buf = ByteBuffer.wrap(payload)
        val nameLen = buf.int
        val name = ByteArray(nameLen)
        buf.get(name)
        val roleLen = buf.int
        val role = ByteArray(roleLen)
        buf.get(role)
        val key = if (buf.remaining() >= 4) {
            val keyLen = buf.int
            require(keyLen in 0..32) { "Invalid room key length" }
            val keyBytes = ByteArray(keyLen)
            buf.get(keyBytes)
            String(keyBytes, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return Triple(
            String(name, StandardCharsets.UTF_8),
            String(role, StandardCharsets.UTF_8),
            key
        )
    }

    fun encodeVideo(flags: Byte, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(1 + data.size)
        buf.put(flags)
        buf.put(data)
        return buf.array()
    }

    fun decodeVideo(payload: ByteArray): Pair<Byte, ByteArray> {
        require(payload.isNotEmpty()) { "Empty video payload" }
        val flags = payload[0]
        val data = payload.copyOfRange(1, payload.size)
        return flags to data
    }

    fun encodeFileMeta(fileName: String, fileSize: Long, transferId: Int): ByteArray {
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 8 + 4 + nameBytes.size)
        buf.putInt(transferId)
        buf.putLong(fileSize)
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        return buf.array()
    }

    fun decodeFileMeta(payload: ByteArray): Triple<Int, Long, String> {
        val buf = ByteBuffer.wrap(payload)
        val id = buf.int
        val size = buf.long
        val nameLen = buf.int
        val name = ByteArray(nameLen)
        buf.get(name)
        return Triple(id, size, String(name, StandardCharsets.UTF_8))
    }

    fun encodeFileChunk(transferId: Int, offset: Long, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 8 + data.size)
        buf.putInt(transferId)
        buf.putLong(offset)
        buf.put(data)
        return buf.array()
    }

    fun decodeFileChunk(payload: ByteArray): Triple<Int, Long, ByteArray> {
        val buf = ByteBuffer.wrap(payload)
        val id = buf.int
        val offset = buf.long
        val data = ByteArray(payload.size - 12)
        buf.get(data)
        return Triple(id, offset, data)
    }

    fun encodeFileDone(transferId: Int): ByteArray {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(transferId)
        return buf.array()
    }

    fun decodeFileDone(payload: ByteArray): Int = ByteBuffer.wrap(payload).int

    fun encodeControl(action: String): ByteArray =
        action.toByteArray(StandardCharsets.UTF_8)

    fun decodeControl(payload: ByteArray): String =
        String(payload, StandardCharsets.UTF_8)
}

data class Packet(val type: Byte, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}
