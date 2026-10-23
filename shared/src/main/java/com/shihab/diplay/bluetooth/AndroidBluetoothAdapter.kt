package com.shihab.diplay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.shihab.diplay.compat.BluetoothCompat
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.BluetoothLinkCheck
import com.shihab.diplay.transport.BluetoothRadioCheck
import com.shihab.diplay.transport.BluetoothRfcommDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Order and budget for one AOSP RFCOMM open.
 *
 * A cached iAP2 record means SDP already finished, so the insecure socket (the link an iPhone
 * paired with "just works" will accept) goes first. Without a cache the first lookup keeps the
 * long KitKat budget. Later attempts stay short: a second full SDP wait rarely succeeds, but an
 * insecure socket can still connect after a secure one stalls on authentication.
 */
internal object AospRfcommPlan {
    enum class Method { SECURE, INSECURE, CHANNEL }

    data class Step(val method: Method, val timeoutMillis: Long)

    fun steps(serviceCached: Boolean): List<Step> = if (serviceCached) {
        listOf(
            Step(Method.INSECURE, 12_000L),
            Step(Method.SECURE, 12_000L),
            Step(Method.CHANNEL, 6_000L),
        )
    } else {
        listOf(
            Step(Method.SECURE, 18_000L),
            Step(Method.INSECURE, 8_000L),
            Step(Method.CHANNEL, 6_000L),
        )
    }
}

class AndroidBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    private val adapterName: String = "AndroidBluetoothAdapter"

    private val adapter: BluetoothAdapter?
        get() = runCatching { BluetoothCompat.getAdapter(context) }.getOrNull()

    override fun isSupported(): Boolean = adapter != null

    override fun isEnabled(): Boolean = runCatching {
        adapter?.isEnabled == true
    }.getOrDefault(false)

    override fun enable(): Boolean = runCatching {
        val a = adapter ?: return false
        if (a.isEnabled) {
            return true
        }
        a.enable()
    }.getOrDefault(false)

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        val a = adapter ?: return emptyList()
        return runCatching {
            (a.bondedDevices ?: emptySet()).map { device ->
                CarPlayBluetoothDevice(
                    name = device.name ?: "Unknown",
                    address = device.address,
                    isConnected = isDeviceConnected(device)
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val paired = getPairedDevices()
        return paired.firstOrNull { it.isConnected }
    }

    override fun localAddress(context: Context): String? {
        return BluetoothAddressResolver.resolve(context)
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        // Radio off / missing adapter are known before the attempt: throw the typed start failure so the
        // host screen can name it instead of silently waiting through an attempt that cannot succeed.
        val a = adapter ?: throw BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER)
        val radioBlocker = BluetoothRadioCheck.blocker(
            adapterPresent = true,
            enabled = isEnabled(),
            pausedByDiPlay = false,
        )
        if (radioBlocker != null) throw BluetoothRadioCheck.failure(radioBlocker)
        val device = a.getRemoteDevice(deviceAddress)
        val serviceUuid = UUID.fromString(uuid)
        // Discovery holds the radio; SDP and socket connect both stall until it stops.
        runCatching { a.cancelDiscovery() }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            runCatching { ensureClassicBond(device) }
        }
        val cached = serviceCached(device, serviceUuid) ||
            runCatching { refreshServiceCache(a, device, serviceUuid) }.getOrDefault(false)

        var pending = AospRfcommPlan.steps(cached)
        var socket: BluetoothSocket? = null
        var lastError: Throwable? = null
        while (pending.isNotEmpty() && socket == null) {
            val step = pending.first()
            pending = pending.drop(1)
            try {
                socket = connectSocket(device, a, step.timeoutMillis) { dev ->
                    when (step.method) {
                        AospRfcommPlan.Method.SECURE ->
                            dev.createRfcommSocketToServiceRecord(serviceUuid)
                        AospRfcommPlan.Method.INSECURE ->
                            dev.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                        AospRfcommPlan.Method.CHANNEL -> {
                            val method = dev.javaClass.getMethod(
                                "createInsecureRfcommSocket",
                                Int::class.javaPrimitiveType,
                            )
                            method.invoke(dev, 1) as BluetoothSocket
                        }
                    }
                }
            } catch (t: Throwable) {
                // A missing channel-1 method must not hide the SDP error that actually failed.
                if (step.method != AospRfcommPlan.Method.CHANNEL || lastError == null) lastError = t
            }
        }

        if (socket == null) {
            // Map a missing Bluetooth socket manager onto a typed BLUETOOTH_UNUSABLE start failure so
            // the host screen matches "cannot open connections for apps" instead of a generic RFCOMM error.
            throw BluetoothLinkCheck.connectFailure(lastError ?: IOException("Bluetooth connect failed"), deviceAddress)
        }

        return BluetoothRfcommDuplexStream(socket)
    }

    @SuppressLint("MissingPermission")
    private fun serviceCached(device: BluetoothDevice, uuid: UUID): Boolean =
        runCatching {
            device.uuids.orEmpty().any { it.uuid == uuid }
        }.getOrDefault(false)

    /** Classic bond. An iPhone will not accept iAP2 RFCOMM on an unpaired or LE-only link. */
    @SuppressLint("MissingPermission")
    private fun ensureClassicBond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        awaitDeviceBroadcast(
            action = BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            address = device.address,
            timeoutMillis = 20_000L,
        ) {
            val started = runCatching {
                // Hidden since KitKat: 1 = TRANSPORT_BREDR. The public createBond() may bond LE only.
                val method = device.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as Boolean
            }.getOrElse {
                runCatching { device.createBond() }.getOrDefault(false)
            }
            started || device.bondState == BluetoothDevice.BOND_BONDING
        }
    }

    /** Ask the phone for its service list so the socket connect does not sit in a cold SDP lookup. */
    @SuppressLint("MissingPermission")
    private fun refreshServiceCache(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        uuid: UUID,
    ): Boolean {
        if (serviceCached(device, uuid)) return true
        awaitDeviceBroadcast(
            action = BluetoothDevice.ACTION_UUID,
            address = device.address,
            timeoutMillis = 8_000L,
        ) {
            runCatching { adapter.cancelDiscovery() }
            runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        }
        return serviceCached(device, uuid)
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun awaitDeviceBroadcast(
        action: String,
        address: String,
        timeoutMillis: Long,
        start: () -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                val extra = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (extra?.address?.equals(address, ignoreCase = true) != true) return
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (state == BluetoothDevice.BOND_BONDING) return
                }
                latch.countDown()
            }
        }
        val app = context.applicationContext
        app.registerReceiver(receiver, IntentFilter(action))
        try {
            if (start()) latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private fun connectSocket(
        device: BluetoothDevice,
        adapter: BluetoothAdapter,
        // KitKat Freescale stacks often need >10s for the first iAP2 RFCOMM.
        timeoutMillis: Long = 18_000L,
        socketFactory: (BluetoothDevice) -> BluetoothSocket,
    ): BluetoothSocket {
        val socket = socketFactory(device)
        val result = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Thread({
            try {
                runCatching { adapter.cancelDiscovery() }
                socket.connect()
            } catch (t: Throwable) {
                result.set(t)
            } finally {
                latch.countDown()
            }
        }, "aosp-rfcomm-connect").apply {
            isDaemon = true
            start()
        }

        val completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            runCatching { socket.close() }
            throw IOException("Timed out connecting AOSP RFCOMM to ${device.address}")
        }

        val err = result.get()
        if (err != null) {
            runCatching { socket.close() }
            if (err is IOException) throw err
            throw IOException("Could not connect RFCOMM", err)
        }

        return socket
    }
}
