// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.host.R

internal class AudioSettingsColumn(page: Fragment) : SettingsColumnKit(page) {
    private fun buildAudioCard(card: LinearLayout) {
        val ctx = card.context
        val buffers = listOf(
            ctx.getString(R.string.setting_buffer_300ms_default),
            ctx.getString(R.string.setting_buffer_500ms),
            ctx.getString(R.string.setting_buffer_1000ms_stable),
        )
        val buf = AirPlayPersistence.loadMediaBufferMillis(ctx)
        val bufIdx = when (buf) { 500 -> 1; 1000 -> 2; else -> 0 }
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_music_buffer),
            capPick(ctx, buffers[bufIdx]) { cap ->
                showDropdown(cap, buffers, bufIdx, syncAnchorText = false) { idx ->
                    GlassSettingsRows.confirmReconnect(host, ctx) {
                        AirPlayPersistence.saveMediaBufferMillis(ctx, listOf(300, 500, 1000)[idx])
                        cap.text = buffers[idx] + " ▾"
                        host.reconnectIfRunning()
                    }
                }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_media_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadMediaAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_media_channel), AirPlayPersistence.loadMediaAudioChannel(ctx)) { v -> AirPlayPersistence.saveMediaAudioChannel(ctx, v) }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_nav_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadNavigationAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_nav_channel), AirPlayPersistence.loadNavigationAudioChannel(ctx)) { v -> AirPlayPersistence.saveNavigationAudioChannel(ctx, v) }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_call_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadCallAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_call_channel), AirPlayPersistence.loadCallAudioChannel(ctx)) { v -> AirPlayPersistence.saveCallAudioChannel(ctx, v) }
            }))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.advanced_audio_channel_mapping), AirPlayPersistence.loadAdvancedAudioChannelMapping(ctx)) { on ->
            AirPlayPersistence.saveAdvancedAudioChannelMapping(ctx, on); host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_main_buffered_audio), AirPlayPersistence.loadMainBufferedAudio(ctx)) { on ->
            AirPlayPersistence.saveMainBufferedAudio(ctx, on); host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_audio_focus_control), AirPlayPersistence.loadAudioFocusEnabled(ctx)) { on ->
            AirPlayPersistence.saveAudioFocusEnabled(ctx, on)
            host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_call_echo_cancellation), AirPlayPersistence.loadCallEchoCancellation(ctx)) { on ->
            AirPlayPersistence.saveCallEchoCancellation(ctx, on); host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_call_voice_filter), AirPlayPersistence.loadCallVoiceFilter(ctx)) { on ->
            AirPlayPersistence.saveCallVoiceFilter(ctx, on); host.reconnectIfRunning()
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_call_speakerphone), AirPlayPersistence.loadCallSpeakerphone(ctx)) { AirPlayPersistence.saveCallSpeakerphone(ctx, it) })
    }

    /** 左列：日常显示（尺寸/缩放/帧率/主题/系统栏/安全区）。 */

    private fun showChannelPicker(anchor: View, title: String, current: Int, save: (Int) -> Unit) {
        val ctx = anchor.context
        val labels = (0..10).map { ctx.getString(R.string.setting_channel_value, it) }
        showDropdown(anchor, labels, current.coerceIn(0, 10), syncAnchorText = false) { idx ->
            GlassSettingsRows.confirmReconnect(host, ctx) {
                save(idx)
                (anchor as? TextView)?.text = labels[idx] + " ▾"
                host.reconnectIfRunning()
            }
        }
    }

    fun fill(card: LinearLayout) = buildAudioCard(card)
}
