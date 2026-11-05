package com.shilapi.xcertplay.transport

import android.content.Context
import android.util.Base64

/**
 * Remembers the Lockdown host identity that an iPhone has already trusted.
 * A new certificate on every plug makes iOS show "Trust This Computer" again.
 */
internal object WiredLockdownStore {
    private const val PREFS = "wired_lockdown_pair"
    private const val HOST_ID = "host_id"
    private const val SYSTEM_BUID = "system_buid"
    private const val WIFI_MAC = "wifi_mac"
    private const val DEVICE_PUBLIC = "device_public"
    private const val DEVICE_CERT = "device_cert"
    private const val HOST_PRIVATE = "host_private"
    private const val HOST_CERT = "host_cert"
    private const val ROOT_PRIVATE = "root_private"
    private const val ROOT_CERT = "root_cert"

    fun load(context: Context): LockdownPairRecord? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(WIFI_MAC, null) ?: return null
        return runCatching {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = decode(prefs.getString(DEVICE_PUBLIC, null)),
                deviceCertificatePem = decode(prefs.getString(DEVICE_CERT, null)),
                hostPrivateKeyPem = decode(prefs.getString(HOST_PRIVATE, null)),
                hostCertificatePem = decode(prefs.getString(HOST_CERT, null)),
                rootPrivateKeyPem = decode(prefs.getString(ROOT_PRIVATE, null)),
                rootCertificatePem = decode(prefs.getString(ROOT_CERT, null)),
            )
        }.getOrNull()
    }

    fun save(context: Context, record: LockdownPairRecord) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST_ID, record.hostId)
            .putString(SYSTEM_BUID, record.systemBuid)
            .putString(WIFI_MAC, record.wifiMacAddress)
            .putString(DEVICE_PUBLIC, encode(record.devicePublicKeyPem))
            .putString(DEVICE_CERT, encode(record.deviceCertificatePem))
            .putString(HOST_PRIVATE, encode(record.hostPrivateKeyPem))
            .putString(HOST_CERT, encode(record.hostCertificatePem))
            .putString(ROOT_PRIVATE, encode(record.rootPrivateKeyPem))
            .putString(ROOT_CERT, encode(record.rootCertificatePem))
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray {
        require(!value.isNullOrBlank())
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
