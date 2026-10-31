// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
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
 * as plain `int`/`float` arguments, which avoids parsing the whole Parcelable `AirConditionInfo`
 * (whose field offsets depend on serializable headers).
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

    /**
     * Resolves (lazily, via reflection) and caches the Coagent AC service binder.
     */
    private fun serviceBinderLocked(): IBinder? {
        serviceBinder?.let { return it }
        val resolved = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, SERVICE_NAME) as? IBinder
        } catch (e: Exception) {
            AppLog.w(TAG, "getService failed", e)
            null
        }
        if (resolved == null) {
            AppLog.w(TAG, "service $SERVICE_NAME not bound")
            return null
        }
        serviceBinder = resolved
        AppLog.i(TAG, "service $SERVICE_NAME bound")
        return resolved
    }

    /** Registers our callback binder with the firmware (transaction 19). Safe to call more than once. */
    fun register() {
        if (registered) return
        val target = serviceBinderLocked() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            callbackBinder ?: newCallbackBinder().also { callbackBinder = it }
            data.writeStrongBinder(callbackBinder!!)
            target.transact(TX_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            registered = true
            AppLog.i(TAG, "registerCallback ok")
        } catch (e: Exception) {
            AppLog.w(TAG, "registerCallback failed", e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Reads current AC type via transaction 1 (fuel vs electric). */
    fun getAcType(): Int {
        val target = serviceBinderLocked() ?: return 0
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            target.transact(TX_GET_AC_TYPE, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            AppLog.w(TAG, "getAcType failed", e)
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
     * The service serves `getAirCondition()` over `reply.writeParcelable(AirConditionInfo, 0)`
     * (confirmed in decompiled `AirConditionService`), so the reply body is an optional leading
     * Parcelable class-name string followed by `AirConditionInfo.writeToParcel`'s flat fields.
     * The field *order* is known (from firmware `AirConditionReport`'s constructor): 4 enum-ish
     * fields, boolean, 3 enum-ish fields, float outside, float inside, 3 enum-ish fields, float
     * left, float right — but the per-field *serialization* (writeInt vs writeString vs
     * writeSerializable; boolean as 1 or 4 bytes) is not documented. Instead of trusting one
     * layout, we decode the reply under every plausible combination and apply the first one whose
     * four temperatures are all plausible and not all-zero; every attempt and the raw bytes are
     * logged to the session log so a wrong guess is fully diagnosable.
     */
    fun refreshSnapshot() {
        val target = serviceBinderLocked() ?: return
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
            log("CoagentAc snapshot reply ${raw.size - base}B: $hexHead")
            val hit = decodeCandidates(reply, base)
            if (hit != null) {
                log("CoagentAc snapshot OK ${hit.layout}: outside=${hit.outside} inside=${hit.inside} left=${hit.left} right=${hit.right}")
                applyTemp(SEAT_OUTSIDE, hit.outside)
                applyTemp(SEAT_INSIDE, hit.inside)
                applyTemp(SEAT_LEFT, hit.left)
                applyTemp(SEAT_RIGHT, hit.right)
            } else {
                log("CoagentAc snapshot: no plausible layout; keeping live values")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "refreshSnapshot failed", e)
            log("CoagentAc refreshSnapshot failed: $e")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private data class Parsed(val layout: String, val outside: Float, val inside: Float, val left: Float, val right: Float)

    private const val ENUM_INT = 0
    private const val ENUM_VAR = 1 // length-prefixed (writeString / writeSerializable both skip as 4+len)

    /** Tries every plausible AirConditionInfo layout and returns the first whose temps are sane. */
    private fun decodeCandidates(reply: Parcel, base: Int): Parsed? {
        // Leading writeParcelable class-name prefix (if the AIDL stub wrote writeParcelable).
        reply.setDataPosition(base)
        val prefix = try { reply.readString() } catch (e: Exception) { null }
        val looksLikeClass = !prefix.isNullOrEmpty() && prefix.length in 4..128 && (prefix.contains('.') || prefix.contains('/'))
        if (looksLikeClass) log("CoagentAc leading string: \"$prefix\" (treated as classname prefix)")
        val prefixStart = if (looksLikeClass) reply.dataPosition() else base
        val startLabels = listOf(
            "noprefix" to base,
            "prefix" to prefixStart,
        ).distinctBy { it.second }
        for ((prefixLabel, start) in startLabels) {
            for (enumMode in listOf(ENUM_INT, ENUM_VAR)) {
                for (boolBytes in listOf(1, 4)) {
                    val name = "$prefixLabel enum=${if (enumMode == ENUM_INT) "int" else "var"} bool=${boolBytes}B"
                    val p0 = start
                    val p1 = skipEnums(reply, p0, 4, enumMode) ?: continue
                    val p2 = p1 + boolBytes
                    val p3 = skipEnums(reply, p2, 3, enumMode) ?: continue
                    val outside = readFloatAt(reply, p3) ?: continue
                    val inside = readFloatAt(reply, p3 + 4) ?: continue
                    val p4 = skipEnums(reply, p3 + 8, 3, enumMode) ?: continue
                    val left = readFloatAt(reply, p4) ?: continue
                    val right = readFloatAt(reply, p4 + 4) ?: continue
                    val sane = outside in -45f..30f && inside in -45f..30f &&
                        left in -5f..40f && right in -5f..40f
                    val allZero = outside == 0f && inside == 0f && left == 0f && right == 0f
                    log("CoagentAc try $name -> o=$outside i=$inside l=$left r=$right ${if (sane && !allZero) "[PASS]" else "[skip]"}")
                    if (sane && !allZero) return Parsed(name, outside, inside, left, right)
                }
            }
        }
        return null
    }

    /** Advances past [count] enum-ish fields, returning the new position or null on overflow. */
    private fun skipEnums(reply: Parcel, from: Int, count: Int, mode: Int): Int? {
        var pos = from
        repeat(count) {
            if (mode == ENUM_INT) {
                pos += 4
            } else {
                if (pos + 4 > reply.dataSize()) return null
                reply.setDataPosition(pos)
                val len = try { reply.readInt() } catch (e: Exception) { return null }
                if (len < 0 || pos + 4 + len > reply.dataSize()) return null
                pos += 4 + len
            }
        }
        return pos
    }

    private fun readFloatAt(reply: Parcel, pos: Int): Float? {
        if (pos + 4 > reply.dataSize()) return null
        reply.setDataPosition(pos)
        return try { reply.readFloat() } catch (e: Exception) { null }
    }

    private fun log(message: String) {
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
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val seat = data.readInt()
                    val temp = data.readFloat()
                    data.readInt() // displayFloatWindow (unused)
                    applyTemp(seat, temp)
                    reply?.writeNoException()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun applyTemp(seat: Int, temp: Float) {
        val changed = when (seat) {
            SEAT_LEFT -> if (leftTemperature != temp) { leftTemperature = temp; true } else false
            SEAT_RIGHT -> if (rightTemperature != temp) { rightTemperature = temp; true } else false
            SEAT_OUTSIDE -> if (outsideTemperature != temp) { outsideTemperature = temp; true } else false
            SEAT_INSIDE -> if (insideTemperature != temp) { insideTemperature = temp; true } else false
            else -> false
        }
        if (changed) {
            AppLog.i(TAG, "temp seat=$seat value=$temp")
            onTemperatureChanged?.invoke()
        }
    }

    /** Drops the cached binder so the next call re-resolves (useful after service restart). */
    fun reset() {
        serviceBinder = null
        registered = false
    }
}
