package com.peerlink.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class LanPeer(
    val name: String,
    val host: String,
    val port: Int,
    val requiresKey: Boolean = false
)

class NsdDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    companion object {
        private const val TAG = "PeerLinkNSD"
        const val SERVICE_TYPE = "_peerlink._tcp."
        const val DEFAULT_PORT = 17890
        private const val ATTR_KEY = "rk"
        /** Avoid '#' — some OEM mDNS stacks mishandle it in instance names. */
        private const val KEY_MARK = "_k"
        private const val LEGACY_KEY_MARK = "#K"
    }

    fun register(
        serviceName: String,
        port: Int = DEFAULT_PORT,
        requiresKey: Boolean = false
    ): Flow<Boolean> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val multicastLock = acquireMulticastLock("peerlink-advertise")
        val base = serviceName
            .replace(KEY_MARK, "")
            .replace(LEGACY_KEY_MARK, "")
            .take(40)
            .ifBlank { "PeerLink-Device" }
        val info = NsdServiceInfo().apply {
            this.serviceName = if (requiresKey) "$base$KEY_MARK" else base
            serviceType = SERVICE_TYPE
            this.port = port
            runCatching { setAttribute(ATTR_KEY, if (requiresKey) "1" else "0") }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered: ${serviceInfo.serviceName}")
                trySend(true)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                trySend(false)
                close()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                close()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: $errorCode")
                close()
            }
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "registerService threw", it)
            trySend(false)
            releaseMulticastLock(multicastLock)
            close(it)
            return@callbackFlow
        }
        awaitClose {
            runCatching { manager.unregisterService(listener) }
            releaseMulticastLock(multicastLock)
        }
    }

    fun discover(): Flow<List<LanPeer>> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val multicastLock = acquireMulticastLock("peerlink-discover")
        val peers = linkedMapOf<String, LanPeer>()
        val selfIps = localIpv4Addresses().toSet()
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun emitPeers() = trySend(peers.values.toList())

        fun peerRequiresKey(serviceInfo: NsdServiceInfo, rawName: String): Boolean {
            if (rawName.contains(KEY_MARK) || rawName.contains(LEGACY_KEY_MARK)) return true
            return runCatching {
                val raw = serviceInfo.attributes?.get(ATTR_KEY) ?: return@runCatching false
                when (raw) {
                    is ByteArray -> String(raw) == "1"
                    else -> raw.toString() == "1"
                }
            }.getOrDefault(false)
        }

        fun displayNameOf(rawName: String): String =
            rawName
                .replace(KEY_MARK, "")
                .replace(LEGACY_KEY_MARK, "")
                .ifBlank { rawName }

        fun hostOf(serviceInfo: NsdServiceInfo): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val modern = serviceInfo.hostAddresses
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?.substringBefore('%')
                if (!modern.isNullOrBlank()) return modern
            }
            @Suppress("DEPRECATION")
            return serviceInfo.host?.hostAddress?.substringBefore('%')
        }

        lateinit var resolveNext: () -> Unit

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed ${serviceInfo.serviceName} code=$errorCode")
                resolving.set(false)
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                try {
                    val host = hostOf(serviceInfo)
                    if (host.isNullOrBlank()) {
                        Log.w(TAG, "Resolved without host: ${serviceInfo.serviceName}")
                        return
                    }
                    if (host in selfIps || host == "127.0.0.1" || host == "::1") {
                        Log.d(TAG, "Skip self: $host")
                        return
                    }
                    val rawName = serviceInfo.serviceName.orEmpty()
                    val displayName = displayNameOf(rawName)
                    val key = "$displayName|$host|${serviceInfo.port}"
                    peers[key] = LanPeer(
                        name = displayName,
                        host = host,
                        port = serviceInfo.port,
                        requiresKey = peerRequiresKey(serviceInfo, rawName)
                    )
                    Log.i(TAG, "Peer: $displayName @ $host:${serviceInfo.port}")
                    emitPeers()
                } finally {
                    resolving.set(false)
                    resolveNext()
                }
            }
        }

        resolveNext = resolve@{
            if (!resolving.compareAndSet(false, true)) return@resolve
            val next = pending.poll()
            if (next == null) {
                resolving.set(false)
                return@resolve
            }
            runCatching {
                @Suppress("DEPRECATION")
                manager.resolveService(next, resolveListener)
            }.onFailure {
                Log.e(TAG, "resolveService threw", it)
                resolving.set(false)
                resolveNext()
            }
        }

        fun enqueueResolve(serviceInfo: NsdServiceInfo) {
            // Deduplicate by instance name while waiting.
            val name = serviceInfo.serviceName
            if (pending.any { it.serviceName == name }) return
            pending.offer(serviceInfo)
            resolveNext()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                close(IllegalStateException("NSD start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType.orEmpty()
                val name = serviceInfo.serviceName.orEmpty()
                val match = type.contains("peerlink", ignoreCase = true) ||
                    name.contains("PeerLink", ignoreCase = true) ||
                    name.contains(KEY_MARK, ignoreCase = true) ||
                    name.contains(LEGACY_KEY_MARK)
                Log.d(TAG, "Found type=$type name=$name match=$match")
                if (match) enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = displayNameOf(serviceInfo.serviceName.orEmpty())
                val before = peers.size
                peers.keys.filter {
                    it.startsWith("$lostName|") || it.startsWith("${serviceInfo.serviceName}|")
                }.forEach { peers.remove(it) }
                if (peers.size != before) emitPeers()
            }
        }

        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.e(TAG, "discoverServices threw", it)
            releaseMulticastLock(multicastLock)
            close(it)
            return@callbackFlow
        }

        awaitClose {
            pending.clear()
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            releaseMulticastLock(multicastLock)
        }
    }

    private fun acquireMulticastLock(tag: String): WifiManager.MulticastLock? {
        return runCatching {
            wifiManager?.createMulticastLock(tag)?.also {
                it.setReferenceCounted(true)
                it.acquire()
                Log.i(TAG, "MulticastLock acquired ($tag)")
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        runCatching {
            if (lock != null && lock.isHeld) {
                lock.release()
                Log.i(TAG, "MulticastLock released")
            }
        }
    }
}

fun localDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    return if (model.isNotEmpty()) "PeerLink-$model" else "PeerLink-Device"
}

fun isLocalAddress(host: String): Boolean {
    if (host == "127.0.0.1" || host == "::1") return true
    return host in localIpv4Addresses()
}
