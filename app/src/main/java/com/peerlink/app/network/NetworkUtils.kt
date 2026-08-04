package com.peerlink.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

fun localIpv4Addresses(): List<String> {
    val result = mutableListOf<String>()
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            nif.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { result += it }
                }
            }
        }
    }
    return result.distinct()
}

fun primaryWifiIpv4(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return localIpv4Addresses().firstOrNull()
        val caps = cm.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val lp: LinkProperties? = cm.getLinkProperties(network)
            val found = lp?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
            if (found != null) return found
        }
    }
    return localIpv4Addresses().firstOrNull()
}
