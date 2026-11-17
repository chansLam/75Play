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

    // Transaction codes from the firmware's ISettingInterface.Stub (AIDL).
    private const val TX_SET_VOLUME_UP = 9
    private const val TX_SET_VOLUME_DOWN = 10
    private const val TX_SET_MUTE = 13
    private const val TX_GET_SETTINGS_INFO = 22
    // Per-channel absolute volume + the explicit UI flag the firmware's WTVolumePanel uses.
    private const val TX_SET_MEDIA_VOLUME = 2
    private const val TX_SET_PHONE_VOLUME = 3
    private const val TX_SET_VOLUME_SHOW = 44

    // The broadcast that flips the head-unit WTVolumePanel into its navigation branch.
    private const val ACTION_NAVI_VOLUME_CHANGE = "com.coagent.intent.action.NAVI_VOLUME_CHANGE"

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

    /** One-arg int call (e.g. setMediaVolume(value)/setPhoneVolume(value)). */
    private fun transactInt(code: Int, value: Int, label: String) {
        val target = binderLocked() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeInt(value)
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
     * 复刻固件 WTVolumePanel 的两个分支，绝不用 adjustStreamVolume（它驱动不了车机面板，
     * 且会让 NAVI 标志卡住、一直显示「导航 3」）：
     *  - 导航播报叠在媒体上时调导航：setStreamVolume(10, …, 0) 后发 NAVI_VOLUME_CHANGE 广播，
     *    WTVolumePanel 收到后把 NAVI 置位并显示导航条（max 10、读 stream10）。
     *  - 音乐 / 无播放：调媒体音量，写入 Android stream 音量后打开车机显示窗口
     *    （setVolumeShow(true)），WTVolumePanel 的 onAudioVolumeChange 把 NAVI 清掉并显示媒体条。
     *  - 通话：同样分通道，写 setPhoneVolume。
     */
    private fun stepActiveStream(direction: Int, fallbackCode: Int, label: String) {
        val navActive = isStreamActive(HEAD_UNIT_NAVIGATION_STREAM, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS)
        val mediaActive = isStreamActive(AudioManager.STREAM_MUSIC, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS)
        val callActive = isStreamActive(AudioManager.STREAM_VOICE_CALL, HEAD_UNIT_VOLUME_ACTIVE_PAST_MS)
        when (headUnitVolumeChannel(callActive, navActive, mediaActive)) {
            HeadUnitVolumeChannel.CALL -> stepChannelVolume(AudioManager.STREAM_VOICE_CALL, TX_SET_PHONE_VOLUME, direction, label)
            HeadUnitVolumeChannel.NAVIGATION -> stepNavigationVolume(direction, label)
            HeadUnitVolumeChannel.MEDIA -> stepChannelVolume(AudioManager.STREAM_MUSIC, TX_SET_MEDIA_VOLUME, direction, label)
        }
    }

    /** 导航：复刻固件 setNaviVol，调 stream 10 并用广播让 WTVolumePanel 走导航分支。 */
    private fun stepNavigationVolume(direction: Int, label: String) {
        val ctx = debugContext
        val am = audioManager()
        if (ctx == null || am == null) {
            AppLog.w(TAG, "$label nav skipped: context/audio unavailable")
            return
        }
        try {
            val current = am.getStreamVolume(HEAD_UNIT_NAVIGATION_STREAM)
            val max = am.getStreamMaxVolume(HEAD_UNIT_NAVIGATION_STREAM)
            val next = (current + if (direction == AudioManager.ADJUST_RAISE) 1 else -1).coerceIn(0, max)
            if (next != current) am.setStreamVolume(HEAD_UNIT_NAVIGATION_STREAM, next, 0)
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

    /**
     * 媒体/通话：实际播放的 AudioTrack 在 Android stream 上，所以必须把音量写进该 stream，
     * 否则 MCU 的 setMediaVolume/setPhoneVolume 只调功放、不动 Android 音量，按了没效果。
     * 写完 stream 后打开车机显示窗口，让 WTVolumePanel 显示对应声道面板。
     */
    private fun stepChannelVolume(stream: Int, setTx: Int, direction: Int, label: String) {
        val am = audioManager()
        if (am == null) {
            AppLog.w(TAG, "$label stream=$stream skipped: audio unavailable")
            return
        }
        try {
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val next = (current + if (direction == AudioManager.ADJUST_RAISE) 1 else -1).coerceIn(0, max)
            if (next != current) am.setStreamVolume(stream, next, 0)
            transactBool(TX_SET_VOLUME_SHOW, true, "setVolumeShow")
            transactInt(setTx, next, if (setTx == TX_SET_PHONE_VOLUME) "setPhoneVolume" else "setMediaVolume")
            AppLog.i(TAG, "$label stream=$stream $current->$next")
            diag("$label s$stream $current->$next")
        } catch (e: Exception) {
            AppLog.w(TAG, "$label stream=$stream failed", e)
            diag("$label s$stream fail:${e.javaClass.simpleName}")
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
 * 音量通道路由：通话 > 导航 > 媒体。任一为 null（反射失败）都按未活跃处理，
 * 全不活跃时按媒体处理，保证按键始终有反馈。
 */
internal enum class HeadUnitVolumeChannel { CALL, NAVIGATION, MEDIA }

internal fun headUnitVolumeChannel(
    callActive: Boolean?,
    navigationActive: Boolean?,
    mediaActive: Boolean?,
): HeadUnitVolumeChannel = when {
    callActive == true -> HeadUnitVolumeChannel.CALL
    navigationActive == true -> HeadUnitVolumeChannel.NAVIGATION
    else -> HeadUnitVolumeChannel.MEDIA
}
