package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.util.Collections
import java.util.WeakHashMap

/** KitKat exposes only the first configuration and omits alternate-setting IDs in Java. */
internal object KitKatUsbDescriptors {
    data class Endpoint(val address: Int, val attributes: Int, val packetSize: Int, val interval: Int)
    data class Interface(val id: Int, val alternate: Int, val clazz: Int, val subclass: Int,
        val protocol: Int, val endpoints: MutableList<Endpoint> = mutableListOf(),
        /** CDC Union slave interface id declared by this interface's class-specific descriptor. */
        var unionSlave: Int? = null,
        /**
         * bmNetworkCapabilities from the CDC-NCM functional descriptor (subtype 0x1A). A device is
         * only obliged to answer SET_MAX_DATAGRAM_SIZE / SET_NTB_INPUT_SIZE when the matching bit
         * is set, and may legitimately STALL them otherwise.
         */
        var ncmCapabilities: Int? = null)
    data class Configuration(val id: Int, val interfaces: MutableList<Interface> = mutableListOf()) {
        /**
         * control interface id -> data interface id, from the CDC Union descriptors.
         *
         * This must be a getter rather than a cached val: the Configuration is constructed (and
         * added to the parse result) before its interfaces' unionSlave fields are filled in, so a
         * val evaluated at construction time would stay empty forever.
         */
        val unions: Map<Int, Int> get() = interfaces.mapNotNull { iface ->
            iface.unionSlave?.let { iface.id to it }
        }.toMap()
        /** Raw configuration descriptor bytes, kept for hex dumps in the diagnostic log. */
        var raw: ByteArray? = null
    }
    private val alternates = Collections.synchronizedMap(WeakHashMap<UsbInterface, Int>())

    fun alternate(iface: UsbInterface): Int? = alternates[iface]

    fun read(connection: UsbDeviceConnection): List<CarPlayUsbConfiguration> {
        val raw = connection.rawDescriptors ?: return emptyList()
        var configurations = parse(raw)
        // Read all configurations through the authorized endpoint 0 if the vendor framework
        // returns only the device descriptor. Never guess configuration 1 or 6.
        if (raw.size >= 18 && raw[1].toInt() == 1 && configurations.size < (raw[17].toInt() and 255)) {
            configurations = (0 until (raw[17].toInt() and 255).coerceAtMost(8)).flatMap { index ->
                val header = ByteArray(9)
                val got = connection.controlTransfer(0x80, 6, 0x0200 or index, 0, header, 9, 1000)
                val size = if (got == 9) u16(header, 2) else 0
                if (size !in 9..65535) emptyList() else {
                    val bytes = ByteArray(size)
                    val count = connection.controlTransfer(0x80, 6, 0x0200 or index, 0, bytes, size, 2000)
                    if (count != size) emptyList() else parse(bytes)
                }
            }
        }
        return configurations.map { config ->
            CarPlayUsbConfiguration(
                config.id,
                config.interfaces.map(::platformInterface),
                config.raw,
                config.unions,
                config.interfaces.mapNotNull { iface ->
                    iface.ncmCapabilities?.let { iface.id to it }
                }.toMap(),
            )
        }
    }

    fun parse(bytes: ByteArray): List<Configuration> {
        val result = mutableListOf<Configuration>()
        var cursor = 0
        var end = 0
        var configuration: Configuration? = null
        var iface: Interface? = null
        while (cursor + 2 <= bytes.size) {
            val length = bytes[cursor].toInt() and 255
            if (length < 2 || cursor + length > bytes.size) return emptyList()
            if (configuration != null && cursor < end && cursor + length > end) return emptyList()
            when (bytes[cursor + 1].toInt() and 255) {
                2 -> {
                    if (length < 9) return emptyList()
                    if (configuration != null && cursor < end) return emptyList()
                    val total = u16(bytes, cursor + 2)
                    if (total < length || cursor + total > bytes.size) return emptyList()
                    configuration = Configuration(bytes[cursor + 5].toInt() and 255).also {
                        it.raw = bytes.copyOfRange(cursor, minOf(cursor + total, bytes.size))
                        result.add(it)
                    }
                    end = cursor + total
                    iface = null
                }
                4 -> {
                    if (configuration == null || cursor >= end || length < 9) return emptyList()
                    iface = Interface(bytes[cursor + 2].toInt() and 255, bytes[cursor + 3].toInt() and 255,
                        bytes[cursor + 5].toInt() and 255, bytes[cursor + 6].toInt() and 255,
                        bytes[cursor + 7].toInt() and 255).also(configuration.interfaces::add)
                }
                5 -> {
                    if (iface == null || cursor >= end || length < 7) return emptyList()
                    iface.endpoints += Endpoint(bytes[cursor + 2].toInt() and 255,
                        bytes[cursor + 3].toInt() and 255, u16(bytes, cursor + 4), bytes[cursor + 6].toInt() and 255)
                }
                // Class-specific interface descriptor. Then: type 0x24, subtype 0x06 = CDC Union
                // (control -> data pairing), subtype 0x1A = CDC-NCM functional descriptor whose
                // bmNetworkCapabilities says which SET requests the device must answer.
                0x24 -> {
                    if (iface != null && cursor < end && length >= 3) {
                        when (bytes[cursor + 2].toInt() and 255) {
                            0x06 -> if (length >= 5) {
                                iface.unionSlave = bytes[cursor + 4].toInt() and 255
                            }
                            0x1a -> if (length >= 6) {
                                iface.ncmCapabilities = bytes[cursor + 5].toInt() and 255
                            }
                        }
                    }
                }
            }
            cursor += length
        }
        return if (cursor == bytes.size) result else emptyList()
    }

    internal fun platformInterface(info: Interface): UsbInterface {
        val primitive = Int::class.javaPrimitiveType!!
        val endpoints = info.endpoints.map {
            UsbEndpoint::class.java.getDeclaredConstructor(primitive, primitive, primitive, primitive)
                .apply { isAccessible = true }.newInstance(it.address, it.attributes, it.packetSize, it.interval)
        }.toTypedArray()
        // API 21+ UsbInterface carries an alternate-setting and a name slot in its hidden
        // constructor; the 5-arg form below is the pre-21 shape. Try the modern one first
        // so direct-mode descriptor parsing (issue #266) works on newer ROMs too; the
        // alternates map is registered either way for the legacy lookup path.
        // Android UsbInterface constructor shapes vary across releases:
        // - Android 5.0+ (API 21-28+ framework and Robolectric): 6-arg (id, alternate, name, clazz, subclass, protocol)
        //   with endpoints populated via setEndpoints([Landroid.os.Parcelable;).
        // - Android 5.0+ (some vendor ROMs): 7-arg (id, alternate, name, clazz, subclass, protocol, endpoints).
        // - Android 4.4 (API 19): 5-arg (id, clazz, subclass, protocol, [Landroid.os.Parcelable;).
        val iface = try {
            val constructor = UsbInterface::class.java.getDeclaredConstructor(
                primitive, primitive, String::class.java, primitive, primitive, primitive,
            ).apply { isAccessible = true }
            val instance = constructor.newInstance(info.id, info.alternate, null, info.clazz, info.subclass, info.protocol)
            runCatching {
                val setEndpointsMethod = UsbInterface::class.java.getDeclaredMethod(
                    "setEndpoints", Array<android.os.Parcelable>::class.java,
                ).apply { isAccessible = true }
                setEndpointsMethod.invoke(instance, endpoints)
            }
            instance
        } catch (_: Exception) {
            try {
                UsbInterface::class.java.getDeclaredConstructor(
                    primitive, primitive, String::class.java, primitive, primitive, primitive,
                    Array<android.os.Parcelable>::class.java,
                ).apply { isAccessible = true }
                    .newInstance(info.id, info.alternate, null, info.clazz, info.subclass, info.protocol, endpoints)
            } catch (_: Exception) {
                try {
                    UsbInterface::class.java.getDeclaredConstructor(
                        primitive, primitive, String::class.java, primitive, primitive, primitive,
                        Array<UsbEndpoint>::class.java,
                    ).apply { isAccessible = true }
                        .newInstance(info.id, info.alternate, null, info.clazz, info.subclass, info.protocol, endpoints)
                } catch (_: Exception) {
                    UsbInterface::class.java.getDeclaredConstructor(
                        primitive, primitive, primitive, primitive,
                        Array<android.os.Parcelable>::class.java,
                    ).apply { isAccessible = true }
                        .newInstance(info.id, info.clazz, info.subclass, info.protocol, endpoints)
                }
            }
        }
        alternates[iface] = info.alternate
        return iface
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
}
