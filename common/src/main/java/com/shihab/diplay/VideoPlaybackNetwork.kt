package com.shihab.diplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** API 23 active-network summary. Loaded only when the real SDK is at least 23. */
internal object VideoPlaybackNetwork {
    @JvmStatic
    fun summary(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        if (network == null || capabilities == null) return "network=none"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return "network=${transports.ifEmpty { listOf("other") }.joinToString("+")} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }
}
