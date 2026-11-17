package com.shihab.diplay.host

import android.content.Context
import android.media.AudioManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.shihab.diplay.AirPlayPersistence
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

    // 固件 MiscService 用它触发 WTVolumePanel 的导航音量条。
    private const val ACTION_NAVI_VOLUME_CHANGE = "com.coagent.intent.action.NAVI_VOLUME_CHANGE"

    // Transaction codes from the firmware's ISettingInterface.Stub (AIDL).
    private const val TX_SET_VOLUME_UP = 9
    private const val TX_SET_VOLUME_DOWN = 10
    private const val TX_SET_MUTE = 13
    private const val TX_GET_SETTINGS_INFO = 22
    // Streams whose volume we snapshot around a volume key so we can see which one the firmware edits.
    private val DIAG_STREAMS = intArrayOf(0, 3, 4, 5, 6, 8, 9, 10)

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
        val isVolumeKey = code == TX_SET_VOLUME_UP || code == TX_SET_VOLUME_DOWN
        val before = if (isVolumeKey) snapshotStreamVolumes() else null
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
        if (before != null) logStreamVolumeDelta(code, before)
    }

    /** Snapshot getStreamVolume() for every diagnostic stream (null when no context/audio service). */
    private fun snapshotStreamVolumes(): IntArray? {
        val am = audioManager() ?: return null
        return DIAG_STREAMS.map { am.getStreamVolume(it) }.toIntArray()
    }

    /** Logs before->after volume for each stream and flags the one the firmware actually changed. */
    private fun logStreamVolumeDelta(code: Int, before: IntArray) {
        val ctx = debugContext
        val am = audioManager() ?: return
        val parts = ArrayList<String>()
        for (i in DIAG_STREAMS.indices) {
            val stream = DIAG_STREAMS[i]
            val after = am.getStreamVolume(stream)
            val changed = if (after != before[i]) " *CHANGED*" else ""
            val active = isStreamActive(stream)?.let { if (it) " active" else "" } ?: ""
            parts.add("s$stream:${before[i]}->$after$active$changed")
        }
        val config = if (ctx == null) "" else {
            val media = AirPlayPersistence.loadMediaAudioChannel(ctx)
            val nav = AirPlayPersistence.loadNavigationAudioChannel(ctx)
            val call = AirPlayPersistence.loadCallAudioChannel(ctx)
            " cfg[media=$media nav=$nav call=$call]"
        }
        diag("tx$code$config delta " + parts.joinToString(" ") + " musicActive=" + am.isMusicActive)
    }

    private fun audioManager(): AudioManager? =
        debugContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Reflects hidden AudioSystem.isStreamActive(stream, inPastMs); null if unavailable. */
    private fun isStreamActive(stream: Int, inPastMs: Int = 0): Boolean? = try {
        val method = Class.forName("android.media.AudioSystem")
            .getMethod("isStreamActive", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        method.invoke(null, stream, inPastMs) as? Boolean
    } catch (e: Exception) {
        null
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

    /** Press volume up (single step) on the stream that is actually playing. */
    fun volumeUp() = stepActiveStream(AudioManager.ADJUST_RAISE, TX_SET_VOLUME_UP, "volUp")

    /** Press volume down (single step) on the stream that is actually playing. */
    fun volumeDown() = stepActiveStream(AudioManager.ADJUST_LOWER, TX_SET_VOLUME_DOWN, "volDown")

    /**
     * 导航播报叠在媒体上时要调的是导航。句间停顿不算没声音，所以回看 [HEAD_UNIT_VOLUME_ACTIVE_PAST_MS]。
     *
     * 导航独立调节 stream 10 并广播 WTVolumePanel；媒体/通话恢复车机原生 setVolumeUp/Down。
     * 绝对值 setMediaVolume/setPhoneVolume 在这台车机上只写设置、不实际改变媒体输出音量。
     */
    private fun stepActiveStream(direction: Int, fallbackCode: Int, label: String) {
        // The renderer can use any legacy stream in 1..20.  Looking only at the
        // framework defaults (3/10/0) makes a configured media stream invisible to
        // AudioSystem.isStreamActive(), so volume keys fall back to the wrong channel.
        val ctx = debugContext
        val configuredMedia = ctx?.let(AirPlayPersistence::loadMediaAudioChannel) ?: AudioManager.STREAM_MUSIC
        val configuredNavigation = ctx?.let(AirPlayPersistence::loadNavigationAudioChannel) ?: HEAD_UNIT_NAVIGATION_STREAM
        val configuredCall = ctx?.let(AirPlayPersistence::loadCallAudioChannel) ?: AudioManager.STREAM_VOICE_CALL
        val mediaStream = configuredMedia.takeIf { it != 0 } ?: AudioManager.STREAM_MUSIC
        val navigationStream = configuredNavigation.takeIf { it != 0 } ?: HEAD_UNIT_NAVIGATION_STREAM
        val callStream = configuredCall.takeIf { it != 0 } ?: AudioManager.STREAM_VOICE_CALL
        val stream = headUnitVolumeStream(
            isStreamActive(navigationStream, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS),
            isStreamActive(mediaStream, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS),
            isStreamActive(callStream, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS),
            navigationStream = navigationStream,
            mediaStream = mediaStream,
            callStream = callStream,
        )
        if (stream == navigationStream) {
            stepNavigationVolume(navigationStream, direction, label)
        } else {
            // Relative MCU steps change the real output volume. The absolute per-channel writes
            // tried in 2074fb3 changed the displayed value but left CarPlay media volume unchanged.
            transactVoid(fallbackCode, label)
        }
    }

    /** 单步调节已配置的导航流并通过车机广播显示 WTVolumePanel，不弹 AOSP 原生面板。 */
    private fun stepNavigationVolume(stream: Int, direction: Int, label: String) {
        val ctx = debugContext
        val am = audioManager()
        if (ctx == null || am == null) {
            AppLog.w(TAG, "$label nav skipped: context/audio unavailable")
            return
        }
        try {
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val next = (current + if (direction == AudioManager.ADJUST_RAISE) 1 else -1)
                .coerceIn(0, max)
            if (next != current) {
                // flag 0：不触发 AOSP 原生音量条。
                am.setStreamVolume(stream, next, 0)
            }
            val intent = android.content.Intent(ACTION_NAVI_VOLUME_CHANGE)
                .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(intent)
            AppLog.i(TAG, "$label nav $current->$next, broadcast WTVolumePanel")
            diag("$label nav $current->$next")
        } catch (e: Exception) {
            AppLog.w(TAG, "$label nav failed", e)
            diag("$label nav fail:${e.javaClass.simpleName}")
        }
    }

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

/** 导航播报的停顿窗口。0 会把句间空隙当成没声音。 */
internal const val HEAD_UNIT_VOLUME_ACTIVE_PAST_MS = 800

/** 车机导航播报所在的音频流。API 19 没有这个公开常量。 */
internal const val HEAD_UNIT_NAVIGATION_STREAM = 10

/**
 * 默认车机声道是媒体 3、导航 10、通话 0；调用方可以传入设置页保存的实际声道。通话优先，
 * 其次导航，最后媒体。反射失败（null）或都没出声时返回 null，交给车机总音量。
 */
internal fun headUnitVolumeStream(
    navigationActive: Boolean?,
    mediaActive: Boolean?,
    callActive: Boolean?,
    navigationStream: Int = HEAD_UNIT_NAVIGATION_STREAM,
    mediaStream: Int = AudioManager.STREAM_MUSIC,
    callStream: Int = AudioManager.STREAM_VOICE_CALL,
): Int? = when {
    callActive == true -> callStream
    navigationActive == true -> navigationStream
    mediaActive == true -> mediaStream
    else -> null
}
