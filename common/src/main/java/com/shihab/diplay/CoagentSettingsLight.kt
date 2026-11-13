package com.shihab.diplay

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

/**
 * Light source for the AMBIENT night mode backed by the head unit's Coagent SettingsService
 * (`coagent.settings`, a system binder service exposing `ISettingInterface`). Instead of the
 * device's own `TYPE_LIGHT` sensor (which this Coagent head unit may not expose / move with the
 * lights), we read the screen-brightness the MCU reports.
 *
 * Why brightness, not the ambient/light-sensor value:
 *   - `ISettingCallBackInterface.onScreenLightNotify` (a would-be "ambient light" callback) is
 *     dead code in the firmware — `updateCurrentScreenLight` has no caller, so the value stays 0
 *     and the callback never fires.
 *   - `onBignessNotify(int)` carries the real, live screen-brightness level the MCU reports via
 *     `MCU_Tx_SettingBrigness` (groupId 17 / subId 7). It is pushed on change AND immediately on
 *     `registerCallBack`, so we always have an initial reading.
 *
 * The brightness is a 0..255 level (higher = brighter). We feed it straight into the controller's
 * lux slot; the effective threshold therefore must be interpreted in brightness terms (see
 * [AmbientLightThreshold]'s range). The controller keeps its debounce / hysteresis logic.
 */
internal class CoagentSettingsLight : CarPlayNightModeController.LightSource {
    private companion object {
        const val TAG = "DiPlay-CoagentLight"
        const val SERVICE_NAME = "coagent.settings"
        // ISettingInterface.AIDL descriptor (matches HeadUnitVolume).
        const val IFACE_DESCRIPTOR = "com.coagent.proxy.binder.service.ISettingInterface"
        // ISettingInterface.Stub transaction codes.
        const val TX_REGISTER_CALLBACK = 25
        const val TX_UNREGISTER_CALLBACK = 26
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var onLux: ((Float) -> Unit)? = null
    private var binder: IBinder? = null
    private var callback: IBinder? = null
    private var ticket = 0

    override val available: Boolean
        get() = resolveBinder() != null

    override fun start(onLux: (Float) -> Unit): Boolean {
        stop()
        val target = resolveBinder() ?: return false
        this.onLux = onLux
        val registered = callback ?: CoagentBrightnessCallback { brightness ->
            val current = ticket
            main.post { deliverIfCurrent(current, brightness) }
        }.also { callback = it }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeStrongBinder(registered)
            target.transact(TX_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            Log.i(TAG, "registerCallback ok")
            true
        } catch (e: Exception) {
            Log.w(TAG, "registerCallback failed", e)
            this.onLux = null
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun stop() {
        ticket++
        onLux = null
        val target = binder
        val registered = callback
        if (target != null && registered != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IFACE_DESCRIPTOR)
                data.writeStrongBinder(registered)
                target.transact(TX_UNREGISTER_CALLBACK, data, reply, 0)
                reply.readException()
            } catch (e: Exception) {
                Log.w(TAG, "unregisterCallback failed", e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun deliverIfCurrent(current: Int, brightness: Int) {
        if (current != ticket) return
        deliver(brightness)
    }

    private fun deliver(brightness: Int) {
        val cb = onLux ?: return
        // Brightness 0..255 -> controller sees it as the "light" value. Negative/invalid = no-op.
        val value = if (brightness >= 0 && brightness <= 255) brightness.toFloat() else Float.NaN
        cb(value)
    }

    private fun resolveBinder(): IBinder? {
        binder?.let { return it }
        val resolved = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, SERVICE_NAME) as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "getService failed", e)
            null
        }
        if (resolved == null) {
            Log.w(TAG, "service $SERVICE_NAME not bound")
            return null
        }
        binder = resolved
        return resolved
    }
}

/**
 * Receives `onBignessNotify` from the head unit. The firmware calls this immediately on register.
 * A thrown [Parcel.enforceInterface] or a reply with no exception header kills this process on
 * the KitKat binder stub, which is the crash when ambient mode is selected.
 */
internal class CoagentBrightnessCallback(
    private val onBrightness: (Int) -> Unit,
) : Binder() {
    internal fun dispatch(data: Parcel, reply: Parcel?): Boolean =
        onTransact(TRANSACTION, data, reply, 0)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACTION) {
            try {
                data.enforceInterface(DESCRIPTOR)
                onBrightness(data.readInt())
            } catch (error: Exception) {
                Log.w(TAG, "brightness callback failed", error)
            }
        }
        if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
            reply?.writeNoException()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    internal companion object {
        private const val TAG = "DiPlay-CoagentLight"
        const val DESCRIPTOR = "com.coagent.proxy.binder.callback.ISettingCallBackInterface"
        const val TRANSACTION = 1
    }
}
