package com.shihab.diplay

import android.content.Context
import android.net.ConnectivityManager

/** API 23 active-network summary. Loaded only when the real SDK is at least 23. */
internal object VideoPlaybackNetwork {
    @JvmStatic
    fun summary(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) ?: return "network=none"
        val network = manager.javaClass.getMethod("getActiveNetwork").invoke(manager)
        val capabilities = network?.let {
            manager.javaClass.getMethod("getNetworkCapabilities", Class.forName("android.net.Network")).invoke(manager, it)
        }
        if (network == null || capabilities == null) return "network=none"
        val type = capabilities.javaClass
        val transports = buildList {
            fun hasTransport(value: Int) = type.getMethod("hasTransport", Int::class.javaPrimitiveType).invoke(capabilities, value) as Boolean
            if (hasTransport(1)) add("wifi")
            if (hasTransport(0)) add("cellular")
            if (hasTransport(3)) add("ethernet")
            if (hasTransport(4)) add("vpn")
        }
        fun hasCapability(value: Int) = type.getMethod("hasCapability", Int::class.javaPrimitiveType).invoke(capabilities, value) as Boolean
        return "network=${transports.ifEmpty { listOf("other") }.joinToString("+")} " +
            "internet=${hasCapability(12)} " +
            "validated=${hasCapability(16)}"
    }
}
