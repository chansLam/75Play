package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.mfi.LocalMfiAuthenticationClient
import com.shihab.diplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val privateRoot = context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        // Fast path: a previously validated install whose stamp still matches. Skip ECDSA
        // round-trip and BC provider warm-up on every cold start — that cost dominates on
        // Freescale / KitKat head units. Missing stamp, APK update, or file change falls through.
        if (target.isDirectory && AUTH_FILES.all { File(target, it).isFile } &&
            stampMatches(target, context)
        ) {
            ready = true
            return
        }
        if (target.isDirectory && AUTH_FILES.all { File(target, it).isFile } &&
            runCatching { LocalMfiAuthenticationClient.load(target) }.isSuccess
        ) {
            writeStamp(target, context)
            ready = true
            return
        }
        val staging = File(privateRoot, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        try {
            for (name in AUTH_FILES) {
                val file = File(staging, name)
                context.assets.open("offline-mfi/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }

            // Validate the APK-bundled identity before it can replace a previous installation.
            LocalMfiAuthenticationClient.load(staging)
            val currentValid = runCatching { LocalMfiAuthenticationClient.load(target) }.isSuccess
            if (!currentValid || !sameAuthenticationFiles(target, staging)) {
                val previous = File(privateRoot, "offline-mfi-previous")
                previous.deleteRecursively()
                if (target.exists()) {
                    check(target.renameTo(previous)) { "Could not rotate previous local authentication" }
                }
                try {
                    check(staging.renameTo(target)) { "Could not install local authentication" }
                    previous.deleteRecursively()
                } catch (error: Throwable) {
                    if (!target.exists() && previous.exists()) previous.renameTo(target)
                    throw error
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        LocalMfiAuthenticationClient.load(target)
        writeStamp(target, context)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    private fun sameAuthenticationFiles(first: File, second: File): Boolean =
        AUTH_FILES.all { name ->
            val left = File(first, name)
            val right = File(second, name)
            left.isFile && right.isFile &&
                MessageDigest.isEqual(sha256(left), sha256(right))
        }

    private fun sha256(file: File): ByteArray =
        file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }

    /** Length + mtime of each auth file plus APK versionCode. Cheap to read; invalidates on update. */
    private fun stampPayload(target: File, context: Context): String {
        val versionCode = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
        val files = AUTH_FILES.joinToString("|") { name ->
            val file = File(target, name)
            "$name:${file.length()}:${file.lastModified()}"
        }
        return "v1;$versionCode;$files"
    }

    private fun stampFile(target: File): File = File(target, STAMP_NAME)

    private fun stampMatches(target: File, context: Context): Boolean {
        val stamp = stampFile(target)
        if (!stamp.isFile) return false
        return runCatching { stamp.readText() == stampPayload(target, context) }.getOrDefault(false)
    }

    private fun writeStamp(target: File, context: Context) {
        val stamp = stampFile(target)
        runCatching {
            stamp.writeText(stampPayload(target, context))
            stamp.setReadable(false, false); stamp.setReadable(true, true)
            stamp.setWritable(false, false); stamp.setWritable(true, true)
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    private val AUTH_FILES = listOf("identity.pk8", "certificate.p7b")
    private const val STAMP_NAME = ".validated"
}

internal enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"), WIRELESS("wireless");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode = entries.firstOrNull { it.key == key } ?: LAST_USED
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun defaultConnectionMode(context: Context) =
        DefaultConnectionMode.fromKey(prefs(context).getString("default_connection_mode", null))
    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        prefs(context).edit().putString("default_connection_mode", mode.key).apply()
    }
    fun autoConnectWireless(context: Context) =
        defaultConnectionMode(context).wireless(AirPlayPersistence.loadWirelessEnabled(context))
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }

    /** Home app grid + CarPlay app panel share this file (not the session prefs). */
    private fun homeApps(context: Context) =
        context.getSharedPreferences(HOME_APP_LIST, Context.MODE_PRIVATE)

    fun homeAppHidden(context: Context): Set<String> =
        homeApps(context).getString(KEY_HIDDEN_PACKAGES, "")?.split(",").orEmpty()
            .filter { it.isNotBlank() }.toSet()

    fun saveHomeAppHidden(context: Context, packages: Set<String>) {
        // commit so a following grid reload sees the new set.
        homeApps(context).edit().putString(KEY_HIDDEN_PACKAGES, packages.joinToString(",")).commit()
    }

    fun homeAppOrder(context: Context): List<String> =
        homeApps(context).getString(KEY_APP_ORDER, "")?.split(",").orEmpty()
            .filter { it.isNotBlank() }

    fun saveHomeAppOrder(context: Context, packages: List<String>) {
        homeApps(context).edit().putString(KEY_APP_ORDER, packages.joinToString(",")).apply()
    }

    private const val HOME_APP_LIST = "home_app_list"
    private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
    private const val KEY_APP_ORDER = "app_order"
}
