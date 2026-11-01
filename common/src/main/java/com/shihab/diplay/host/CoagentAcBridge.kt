// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.util.AppLog

/**
 * Bridges to the head unit's Coagent air-condition service (`coagent.ac`, a system binder service
 * exposing `IAirConditionInterface`) so this app can read live AC temperatures — outside,
 * inside and the driver/passenger set temperatures — for the global top temperature bar.
 *
 * The service is reached through reflection on the hidden `ServiceManager` (API 19) and driven
 * with raw `Parcel.transact()` using the exact transaction codes and interface token the
 * firmware's AIDL stub expects (same technique as [HeadUnitVolume] for `coagent.settings`).
 *
 * We register an [IAirConditionCallback] implementation (itself a binder) via transaction 19 so the
 * firmware pushes temperature updates to us — no polling. The callback carries the values we need
 * as plain `int`/`float` arguments. On startup we additionally pull `getAirConditionInfo()`
 * (transaction 27) once and parse `AirConditionInfo` with the exact `writeToParcel` layout from the
 * head unit's own AIDL classes (decompiled from `S311WT_SystemUI`), so the three temps render even
 * before the firmware emits its first change.
 *
 * Callback seat (type) values confirmed in firmware `AirConditionReport`:
 *   0 = leftTemperature  1 = rightTemperature  3 = outsideTemperature  4 = insideTemperature
 */
object CoagentAcBridge {
    private const val TAG = "DiPlay-CoagentAc"

    private const val SERVICE_NAME = "coagent.ac"
    private const val IFACE_DESCRIPTOR = "com.incall.proxy.ac.IAirConditionInterface"
    private const val CALLBACK_DESCRIPTOR = "com.incall.proxy.ac.IAirConditionCallback"

    // IAirConditionInterface.Stub transaction codes.
    private const val TX_GET_AC_TYPE = 1
    private const val TX_GET_AC_INFO = 27
    private const val TX_REGISTER_CALLBACK = 19

    // Callback seat values.
    const val SEAT_LEFT = 0
    const val SEAT_RIGHT = 1
    const val SEAT_OUTSIDE = 3
    const val SEAT_INSIDE = 4

    /** Latest AC temperatures (updated on callback, in °C). -40 means "invalid". */
    @Volatile var outsideTemperature = Float.NaN
        private set
    @Volatile var insideTemperature = Float.NaN
        private set
    @Volatile var leftTemperature = Float.NaN
        private set
    @Volatile var rightTemperature = Float.NaN
        private set

    /** Called on the binder thread whenever any AC temperature changes. */
    @Volatile var onTemperatureChanged: (() -> Unit)? = null

    private var serviceBinder: IBinder? = null
    private var callbackBinder: IBinder? = null
    private var registered = false

    /** True once [refreshSnapshot] has parsed a real AirConditionInfo layout. */
    @Volatile private var snapshotReady = false

    // The firmware service can register with ServiceManager well after this app's process starts
    // (head-unit boot sequence), so a single startup attempt misses it permanently. Retry on a
    // fixed cadence until the service appears, then stop.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var attempts = 0
    private val seenSeats = HashSet<Int>()

    // SystemUI's ServiceConnectionManager links a death recipient to the coagent.ac binder and
    // restarts polling when it dies, so an AC-service restart (or a service that re-registers) is
    // reconnected automatically instead of leaving the bar frozen. Mirror that here: we only need
    // the recipient registered on the live binder; the latch re-opens the polling loop on death.
    private val binderDeathRecipient = android.os.IBinder.DeathRecipient { onServiceBinderDied() }

    private const val RETRY_INTERVAL_MS = 2000L
    private const val MAX_ATTEMPTS = 30 // ~60s of retries after first show()

    /**
     * Resolves (lazily, via reflection) and caches the Coagent AC service binder.
     */
    private fun serviceBinderLocked(): IBinder? {
        serviceBinder?.let { cached ->
            val alive = runCatching { cached.pingBinder() }.getOrDefault(false)
            if (alive) return cached
            diag("coagent.ac cached binder died; re-resolving")
            serviceBinder = null
        }
        val resolved = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, SERVICE_NAME) as? IBinder
        } catch (e: Exception) {
            diag("coagent.ac getService reflection failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (resolved == null) {
            diag("coagent.ac getService returned null (service not registered yet / not visible)")
            return null
        }
        // Verify the binder is alive and expose its interface descriptor so a wrong service name
        // or a dead handle shows up in the diagnostic report instead of failing silently in
        // transact().
        val alive = runCatching { resolved.pingBinder() }.getOrDefault(false)
        val descriptor = runCatching { resolved.interfaceDescriptor }.getOrNull() ?: "unknown"
        diag("coagent.ac binder resolved alive=$alive descriptor=$descriptor")
        serviceBinder = resolved
        runCatching { resolved.linkToDeath(binderDeathRecipient, 0) }
            .onFailure { diag("coagent.ac linkToDeath failed: ${it.javaClass.simpleName}: ${it.message}") }
        return resolved
    }

    /** Called on a background binder thread when the coagent.ac service process dies or restarts. */
    private fun onServiceBinderDied() {
        diag("coagent.ac binder died; dropping cached handles and reconnecting")
        serviceBinder = null
        registered = false
        callbackBinder = null
        snapshotReady = false
        seenSeats.clear()
        // Re-open the polling loop from scratch (it is closed once we settled, so startRetries
        // schedules a fresh attempt immediately; if one is already running it simply continues).
        startRetries()
    }

    /** Registers our callback binder with the firmware (transaction 19). Safe to call more than once. */
    fun register() {
        if (registered) return
        val target = serviceBinderLocked() ?: run {
            diag("coagent.ac register skipped: binder unavailable (retry pending)")
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            callbackBinder ?: newCallbackBinder().also { callbackBinder = it }
            data.writeStrongBinder(callbackBinder!!)
            target.transact(TX_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            registered = true
            diag("coagent.ac registerCallback tx19 OK")
        } catch (e: Exception) {
            diag("coagent.ac registerCallback tx19 FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Starts the late-ready polling loop: register() + refreshSnapshot() every [RETRY_INTERVAL_MS]
     * until both succeed or [MAX_ATTEMPTS] is reached. Idempotent.
     */
    fun startRetries() {
        if (retryRunnable != null) return
        attempts = 0
        scheduleNext(0L)
    }

    /** One-shot register + refresh attempt (called from Activity onResume/onStart); does not cancel the loop. */
    fun nudge() {
        register()
        refreshSnapshot()
    }

    private fun scheduleNext(delayMs: Long) {
        val r = object : Runnable {
            override fun run() {
                retryRunnable = this
                attempts++
                register()
                refreshSnapshot()
                val done = registered && snapshotReady
                if (!done && attempts < MAX_ATTEMPTS) {
                    mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
                } else {
                    retryRunnable = null
                    if (attempts >= MAX_ATTEMPTS && !done) {
                        diag("coagent.ac retry gave up after $attempts attempts: registered=$registered snapshotReady=$snapshotReady")
                    } else {
                        diag("coagent.ac retry settled after $attempts attempts: registered=$registered snapshotReady=$snapshotReady")
                    }
                }
            }
        }
        retryRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    /** Reads current AC type via transaction 1 (fuel vs electric). */
    fun getAcType(): Int {
        val target = serviceBinderLocked() ?: run {
            diag("coagent.ac getAcType tx1 skipped: binder unavailable")
            return 0
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            target.transact(TX_GET_AC_TYPE, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            diag("coagent.ac getAcType tx1 FAILED: ${e.javaClass.simpleName}: ${e.message}")
            0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Pulls the current AirConditionInfo (transaction 27) to populate left/right set temperatures
     * on startup. The firmware only pushes onACTempChanged when a value *changes*, so without this
     * the driver/passenger set temperature stays `--` until the user actually adjusts the HVAC.
     *
     * The reply format is taken verbatim from the head unit's own AIDL stub
     * (`getAirConditionInfo` in decompiled `S311WT_SystemUI`):
     * `reply.writeNoException(); reply.writeInt(1); info.writeToParcel(reply, 1)` (writeInt(0) if
     * the service holds no snapshot yet). So after `readException()` we must first consume the
     * 4-byte non-null flag, then read `AirConditionInfo` with the exact field layout of its
     * `writeToParcel` — enum fields are Java-serialization streams (`writeSerializable`, not
     * fixed ints), so [parseInfoExact] uses the matching readers in the documented order.
     */
    fun refreshSnapshot() {
        val target = serviceBinderLocked() ?: run {
            diag("coagent.ac refreshSnapshot skipped: binder unavailable (retry pending)")
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            target.transact(TX_GET_AC_INFO, data, reply, 0)
            reply.readException()
            val base = reply.dataPosition() // after the 4-byte status int
            val raw = reply.marshall()
            val hex = if (base < raw.size) raw.copyOfRange(base, raw.size).joinToString(" ") { "%02x".format(it) } else "empty"
            val hexHead = if (hex.length > 240) hex.substring(0, 240) + "…" else hex
            diag("coagent.ac snapshot tx27 reply ${raw.size - base}B: $hexHead")
            // getAirConditionInfo() serves: [writeNoException status][writeInt(1) non-null flag][AirConditionInfo.writeToParcel].
            val present = reply.readInt() == 1
            if (!present) {
                diag("coagent.ac snapshot tx27: null AirConditionInfo (firmware reports no data yet)")
                return
            }
            val parsed = parseInfoExact(reply)
            if (parsed != null) {
                snapshotReady = true
                diag("coagent.ac snapshot OK ${parsed.layout}: outside=${parsed.outside} inside=${parsed.inside} left=${parsed.left} right=${parsed.right}")
                applyTemp(SEAT_OUTSIDE, parsed.outside)
                applyTemp(SEAT_INSIDE, parsed.inside)
                applyTemp(SEAT_LEFT, parsed.left)
                applyTemp(SEAT_RIGHT, parsed.right)
            } else {
                diag("coagent.ac snapshot tx27: AirConditionInfo layout mismatch (parseInfoExact returned null)")
            }
        } catch (e: Exception) {
            diag("coagent.ac refreshSnapshot tx27 FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private data class Parsed(val layout: String, val outside: Float, val inside: Float, val left: Float, val right: Float)

    /**
     * Parses AirConditionInfo with the exact field layout its AIDL `writeToParcel` emits
     * (confirmed against the head unit's SystemUI: `com.incall.proxy.can.AirConditionInfo`):
     *
     *   4 serializable enums (frontMain/refrigeration/circleStatus/subregion)
     *   + 1 byte flag (isCompressorActive)
     *   + 3 serializable enums (frontSwitch/tempRegionMode/windMode)
     *   + 2 floats (outsideTemperature, insideTemperature)
     *   + 3 serializable enums (frontDefrost/rearDefrost/autoMode)
     *   + 2 floats (leftTemperature, rightTemperature)
     *   + remaining fields we do not need.
     *
     * Enum fields are written with `Parcel.writeSerializable`, whose body is a length-prefixed
     * Java serialization stream — not a fixed 4-byte int — so the earlier heuristic decoder never
     * matched. Using `readSerializable()`/`readByte()`/`readFloat()` in the documented order reads
     * exactly what the firmware writes.
     */
    private fun parseInfoExact(reply: Parcel): Parsed? {
        try {
            if (!skipSerializables(reply, 4)) return null      // frontMain, refrigeration, circleStatus, subregion
            reply.readByte()                                    // isCompressorActive
            if (!skipSerializables(reply, 3)) return null       // frontSwitch, tempRegionMode, windMode
            val outside = reply.readFloat()
            val inside = reply.readFloat()
            if (!skipSerializables(reply, 3)) return null       // frontDefrost, rearDefrost, autoMode
            val left = reply.readFloat()
            val right = reply.readFloat()
            return Parsed("exact", outside, inside, left, right)
        } catch (e: Exception) {
            return null
        }
    }

    /** Skips [count] `writeSerializable` fields by consuming them via the matching reader. */
    private fun skipSerializables(reply: Parcel, count: Int): Boolean {
        repeat(count) {
            try {
                reply.readSerializable()
            } catch (e: Exception) {
                return false
            }
        }
        return true
    }

    /** Every temperature-chain branch goes through here: on-disk diagnostic report + logcat. */
    private fun diag(message: String) {
        AsyncDiagnosticLog.append(ActiveSessionLog.current, message)
        AppLog.w(TAG, message)
    }

    /**
     * Builds the callback binder. When the firmware pushes a temperature update it transacts
     * `IAirConditionCallback.onACTempChanged` (code 7) into our [IBinder] with
     * `int seat, float temp, boolean displayFloatWindow`; we read those and publish them.
     */
    private fun newCallbackBinder(): IBinder {
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 7) {
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val seat = data.readInt()
                        val temp = data.readFloat()
                        val displayFloatWindow = data.readInt() // unused
                        if (seenSeats.add(seat)) {
                            diag("coagent.ac callback onACTempChanged tx7 first receipt seat=$seat temp=$temp display=$displayFloatWindow")
                        }
                        applyTemp(seat, temp)
                    } catch (e: Exception) {
                        diag("coagent.ac callback tx7 read FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    reply?.writeNoException()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun applyTemp(seat: Int, temp: Float) {
        // The firmware may push a placeholder 0.0 on startup, on every re-broadcast without valid
        // sensor data, or (once we already hold a real value) when it re-announces invalid state.
        // An AC temperature of 0.0°C is never a real reading in practice (HVAC set range starts
        // well above 0; outdoor/inside on this car won't hit exactly 0), so we always drop it:
        // never let a placeholder 0.0 replace "--" nor clobber a real value already displayed.
        if (temp == 0f) return
        val changed = when (seat) {
            SEAT_LEFT -> if (leftTemperature != temp) { leftTemperature = temp; true } else false
            SEAT_RIGHT -> if (rightTemperature != temp) { rightTemperature = temp; true } else false
            SEAT_OUTSIDE -> if (outsideTemperature != temp) { outsideTemperature = temp; true } else false
            SEAT_INSIDE -> if (insideTemperature != temp) { insideTemperature = temp; true } else false
            else -> {
                diag("coagent.ac callback: unknown seat=$seat temp=$temp ignored")
                false
            }
        }
        if (changed) {
            diag("coagent.ac temp applied seat=$seat value=$temp")
            onTemperatureChanged?.invoke()
        }
    }

    /** Drops the cached binder so the next call re-resolves (useful after service restart). */
    fun reset() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        serviceBinder = null
        registered = false
        snapshotReady = false
        seenSeats.clear()
    }
}
