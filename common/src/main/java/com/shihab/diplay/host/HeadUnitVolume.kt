package com.shihab.diplay.host

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.shihab.diplay.util.AppLog

/**
 * Bridges to the head unit's Coagent SettingsService (`coagent.settings`, a system binder
 * service exposing `ISettingInterface`) so this app can drive volume / mute exactly like the
 * firmware's own key interrupt path (MiscService -> SettingsService.setVolumeUp/Down/setMute).
 *
 * Why this exists: 75Play keeps key-test mode on while CarPlay is in front. Key-test mode makes
 * the firmware skip all its KeyInterrupts, including MiscService.mVolumeKeyInterrupt and
 * mMuteKeyInterrupt — the very handlers that turn VOLUP/VOLDOWN/MUTE into real volume / mute.
 * So for the three volume keys we call the settings service directly, matching the firmware path.
 *
 * The service is reached through reflection on the hidden `ServiceManager` (API 19) and driven
 * with raw `Parcel.transact()` using the exact transaction codes and interface token the
 * firmware's AIDL stub expects. This avoids `Class.forName` on a class that does not exist in
 * our dex (the very first attempt used `com.coagent.proxy...ISettingInterface$Stub` which was
 * never present, so every call silently no-oped).
 */
object HeadUnitVolume {
    private const val TAG = "DiPlay-HeadUnitVol"
    private const val SERVICE_NAME = "coagent.settings"
    private const val IFACE_DESCRIPTOR = "com.coagent.proxy.binder.service.ISettingInterface"

    // Transaction codes from the firmware's ISettingInterface.Stub (AIDL).
    private const val TX_SET_VOLUME_UP = 9
    private const val TX_SET_VOLUME_DOWN = 10
    private const val TX_SET_MUTE = 13
    private const val TX_GET_SETTINGS_INFO = 22

    private var binder: IBinder? = null
    private var debugContext: Context? = null

    /** Binds a diagnostic context used to surface live results on screen (debug builds). */
    fun attachDebug(ctx: Context?) {
        debugContext = ctx
    }

    private fun diag(msg: String) {
        AppLog.i(TAG, msg)
    }

    /** Resolves (lazily, via reflection) and caches the Coagent settings service binder. */
    private fun binderLocked(): IBinder? {
        binder?.let { return it }
        val resolved = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, SERVICE_NAME) as? IBinder
        } catch (e: Exception) {
            AppLog.w(TAG, "getService failed", e)
            diag("getService fail:${e.javaClass.simpleName}")
            null
        }
        if (resolved == null) {
            AppLog.w(TAG, "service $SERVICE_NAME not bound")
            diag("service not bound")
            return null
        }
        binder = resolved
        diag("binder ok")
        return resolved
    }

    /** One-way no-arg void call, matching the AIDL proxy's exact wire format. */
    private fun transactVoid(code: Int, label: String) {
        val target = binderLocked() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            target.transact(code, data, reply, 0)
            reply.readException()
            AppLog.i(TAG, "tx$code -> ok")
            diag("$label ok")
        } catch (e: Exception) {
            AppLog.w(TAG, "tx$code failed", e)
            diag("$label fail:${e.javaClass.simpleName}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** void call with one boolean argument (e.g. setMute(bool)). */
    private fun transactBool(code: Int, value: Boolean, label: String) {
        val target = binderLocked() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeInt(if (value) 1 else 0)
            target.transact(code, data, reply, 0)
            reply.readException()
            AppLog.i(TAG, "tx$code($value) -> ok")
            diag("$label($value) ok")
        } catch (e: Exception) {
            AppLog.w(TAG, "tx$code failed", e)
            diag("$label fail:${e.javaClass.simpleName}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Reads the current mute flag from `getSettingsInfo()`. The reply writes a non-null flag,
     * then the SettingsInfo parcel directly (writeInt(1) + writeToParcel). `isMute` is the 12th
     * value (8 ints, then the McuVersion string, then isBrignessAuto, currentRTC, isMute).
     */
    private fun currentMute(): Boolean? {
        val target = binderLocked() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            target.transact(TX_GET_SETTINGS_INFO, data, reply, 0)
            reply.readException()
            var mute: Boolean? = null
            if (reply.readInt() == 1) {
                repeat(8) { reply.readInt() }
                reply.readString() // mMcuVersion
                reply.readInt() // isBrignessAuto
                reply.readInt() // currentRTC
                mute = reply.readInt() != 0 // isMute
            }
            diag("mute=$mute")
            mute
        } catch (e: Exception) {
            AppLog.w(TAG, "getSettingsInfo failed", e)
            diag("muteRead fail:${e.javaClass.simpleName}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Press volume up (single step). */
    fun volumeUp() = transactVoid(TX_SET_VOLUME_UP, "volUp")

    /** Press volume down (single step). */
    fun volumeDown() = transactVoid(TX_SET_VOLUME_DOWN, "volDown")

    /** Toggle mute to its opposite. Falls back to unmute when the current state is unknown. */
    fun toggleMute() {
        val muted = currentMute()
        val target = if (muted == null) false else !muted
        AppLog.i(TAG, "toggleMute current=$muted -> set $target")
        transactBool(TX_SET_MUTE, target, "setMute")
    }

    /** Drops the cached binder so the next call re-resolves (useful after service restart). */
    fun reset() {
        binder = null
    }
}
