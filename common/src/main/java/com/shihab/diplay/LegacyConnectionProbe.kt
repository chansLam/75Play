package com.shihab.diplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import javax.net.ssl.SSLContext
import com.shihab.diplay.util.AppLog

/**
 * Read-only diagnostic: never logs identities, network addresses, credentials or USB serials.
 *
 * The USB section is a wired (CDC-NCM) host feasibility probe. It does not run a CarPlay
 * session; it verifies the only hard prerequisite for a userspace lwIP port: that this
 * ROM can enumerate, permission, open, claim and control-transfer an attached iPhone.
 */
internal object LegacyConnectionProbe {
    fun report(context: Context): String = buildString {
        appendLine("Android API=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        appendLine(runCatching {
            val provider = org.conscrypt.Conscrypt.newProvider()
            val ssl = SSLContext.getInstance("TLS", provider).apply { init(null, null, null) }
            val engine = ssl.createSSLEngine()
            "Bundled Conscrypt provider=${provider.name} supported=${engine.supportedProtocols.joinToString()} enabled=${engine.enabledProtocols.joinToString()}"
        }.getOrElse { "Bundled Conscrypt unavailable=${it.javaClass.simpleName}: ${it.message?.take(80)}" })
        for (name in listOf("TLSv1.2", "TLS")) {
            appendLine(runCatching {
                val ssl = SSLContext.getInstance(name).apply { init(null, null, null) }
                val engine = ssl.createSSLEngine()
                "TLS context=$name provider=${ssl.provider.name} supported=${engine.supportedProtocols.joinToString()} enabled=${engine.enabledProtocols.joinToString()}"
            }.getOrElse { "TLS context=$name unavailable=${it.javaClass.simpleName}" })
        }
        append(usbHostSection(context))
        appendLine(runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            "WiFi service=${wifi != null} enabled=${wifi?.isWifiEnabled} p2pFeature=${context.packageManager.hasSystemFeature("android.hardware.wifi.direct")}"
        }.getOrElse { "WiFi probe failed=${it.javaClass.simpleName}" })
        appendLine(runCatching {
            @Suppress("DEPRECATION")
            val bluetooth = BluetoothAdapter.getDefaultAdapter()
            val hybrid = com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(context)
            "Bluetooth adapter=${bluetooth != null} enabled=${bluetooth?.isEnabled} " +
                "hybridSupported=${hybrid.isSupported()} hybridEnabled=${hybrid.isEnabled()} " +
                "hybridAdapter=${hybrid.getAdapterName()}"
        }.getOrElse { "Bluetooth probe failed=${it.javaClass.simpleName}" })
    }

    /**
     * Wired USB host feasibility. Multi-line: one summary line, then per-device detail.
     * No su is executed; only the on-disk su binary presence is reported (no grant dialog).
     */
    internal fun usbHostSection(context: Context): String = runCatching {
        buildString {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val hostFeature = context.packageManager.hasSystemFeature("android.hardware.usb.host")
            val devices = usb?.deviceList?.values.orEmpty()
            appendLine(
                "USB Host feature=$hostFeature service=${usb != null} devices=${devices.size} " +
                    "suBinaryPresent=${RootShell.suBinaryPresent()}",
            )
            if (usb == null) return@buildString
            devices.forEach { device -> append(deviceReport(usb, device)) }
        }
    }.getOrElse {
        "USB Host probe failed=${it.javaClass.simpleName}: ${it.message?.take(80)}\n"
    }

    private fun deviceReport(usb: UsbManager, device: UsbDevice): String = buildString {
        val permission = usb.hasPermission(device)
        appendLine(
            "USB device vid=${device.vendorId} pid=${device.productId} " +
                "class=${className(device.deviceClass)} sub=${device.deviceSubclass} " +
                "proto=${device.deviceProtocol} interfaces=${device.interfaceCount} permission=$permission",
        )
        for (index in 0 until device.interfaceCount) {
            append(interfaceReport(device.getInterface(index)))
        }
        if (!permission) {
            appendLine("  open skipped: no USB permission (grant it, then re-run the probe)")
            return@buildString
        }
        // The strongest proof short of a full CarPlay stack: actually open the connection,
        // claim each interface (force, as the real NCM bridge would), and exercise EP0.
        val connection = usb.openDevice(device)
        if (connection == null) {
            appendLine("  openDevice returned null: framework refused the USB connection")
            return@buildString
        }
        try {
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                val claimed = runCatching { connection.claimInterface(iface, true) }.getOrDefault(false)
                appendLine("  claim iface=${iface.id} ok=$claimed")
                if (claimed) {
                    runCatching { connection.releaseInterface(iface) }
                }
            }
            append(controlTransferReport(connection, device))
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun interfaceReport(iface: UsbInterface): String = buildString {
        val ncm = iface.interfaceClass == UsbConstants.USB_CLASS_COMM &&
            iface.interfaceSubclass == NCM_SUBCLASS
        appendLine(
            "  iface id=${iface.id} class=${className(iface.interfaceClass)} " +
                "sub=0x${iface.interfaceSubclass.toString(16)} proto=${iface.interfaceProtocol} " +
                "endpoints=${iface.endpointCount} ncm=${ncm}",
        )
        for (index in 0 until iface.endpointCount) {
            append(endpointReport(iface.getEndpoint(index)))
        }
    }

    private fun endpointReport(endpoint: UsbEndpoint): String {
        val dir = if (endpoint.address and UsbConstants.USB_ENDPOINT_DIR_MASK == UsbConstants.USB_DIR_IN) "in" else "out"
        return "    ep 0x${endpoint.address.toString(16)} $dir ${xferName(endpoint.type)} " +
            "maxPacket=${endpoint.maxPacketSize}\n"
    }

    /**
     * Two control proofs: a standard device-descriptor read (works on any device), and a
     * CDC-NCM GET_NTB_PARAMETERS read when an NCM control interface is present.
     */
    private fun controlTransferReport(connection: UsbDeviceConnection, device: UsbDevice): String = buildString {
        runCatching {
            val buffer = ByteArray(18)
            val read = connection.controlTransfer(
                UsbConstants.USB_DIR_IN,
                USB_REQ_GET_DESCRIPTOR,
                (USB_DT_DEVICE shl 8),
                0,
                buffer,
                buffer.size,
                CONTROL_TIMEOUT_MS,
            )
            appendLine("  control GET_DEVICE_DESCRIPTOR read=$read ${if (read >= 18) "ok" else "short/failed"}")
        }.onFailure { appendLine("  control GET_DEVICE_DESCRIPTOR failed=${it.javaClass.simpleName}") }

        val ncmControl = firstNcmControl(device)
        if (ncmControl != null) {
            runCatching {
                val buffer = ByteArray(NTB_PARAMETERS_LENGTH)
                val requestType = UsbConstants.USB_DIR_IN or
                    UsbConstants.USB_TYPE_CLASS or
                    USB_RECIP_INTERFACE
                val read = connection.controlTransfer(
                    requestType,
                    USB_CDC_GET_NTB_PARAMETERS,
                    0,
                    ncmControl.id,
                    buffer,
                    buffer.size,
                    CONTROL_TIMEOUT_MS,
                )
                appendLine("  control GET_NTB_PARAMETERS iface=${ncmControl.id} read=$read " +
                    if (read >= NTB_PARAMETERS_LENGTH) "ok" else "short/failed")
            }.onFailure {
                appendLine("  control GET_NTB_PARAMETERS failed=${it.javaClass.simpleName}")
            }
        }
    }

    private fun firstNcmControl(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM &&
                iface.interfaceSubclass == NCM_SUBCLASS
            ) {
                return iface
            }
        }
        return null
    }

    private fun className(value: Int): String = when (value) {
        UsbConstants.USB_CLASS_COMM -> "comm($value)"
        UsbConstants.USB_CLASS_CDC_DATA -> "cdc-data($value)"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "vendor($value)"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "storage($value)"
        UsbConstants.USB_CLASS_HID -> "hid($value)"
        else -> "class($value)"
    }

    private fun xferName(value: Int): String = when (value) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "xfer($value)"
    }

    fun log(context: Context) {
        report(context).lineSequence().forEach { if (it.isNotBlank()) AppLog.i("DiPlayLegacyProbe", it) }
    }

    private const val NCM_SUBCLASS = 0x0d
    private const val USB_RECIP_INTERFACE = 0x01
    private const val USB_REQ_GET_DESCRIPTOR = 0x06
    private const val USB_DT_DEVICE = 0x01
    private const val USB_CDC_GET_NTB_PARAMETERS = 0x80
    private const val NTB_PARAMETERS_LENGTH = 28
    private const val CONTROL_TIMEOUT_MS = 1000
}
