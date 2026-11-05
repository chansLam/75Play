package com.shihab.diplay.host

import android.content.Context

/** One learnable steering-wheel function. */
enum class GenericSwcAction {
    TRACK_NEXT,
    TRACK_PREV,
    ANSWER_CALL,
    END_CALL,
    SIRI,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    MAP_VIEW,
    ;

    fun toOutcome(): SwcWheelOutcome = when (this) {
        TRACK_NEXT -> SwcWheelOutcome.TRACK_NEXT
        TRACK_PREV -> SwcWheelOutcome.TRACK_PREV
        ANSWER_CALL -> SwcWheelOutcome.ANSWER_CALL
        END_CALL -> SwcWheelOutcome.END_CALL
        SIRI -> SwcWheelOutcome.SIRI
        VOLUME_UP -> SwcWheelOutcome.VOLUME_UP
        VOLUME_DOWN -> SwcWheelOutcome.VOLUME_DOWN
        MUTE -> SwcWheelOutcome.MUTE
        MAP_VIEW -> SwcWheelOutcome.MAP_VIEW
    }

    fun label(context: Context): String = context.getString(
        when (this) {
            TRACK_NEXT -> R.string.settings_swc_next
            TRACK_PREV -> R.string.settings_swc_prev
            ANSWER_CALL -> R.string.settings_swc_answer
            END_CALL -> R.string.settings_swc_hangup
            SIRI -> R.string.settings_swc_siri
            VOLUME_UP -> R.string.settings_swc_vol_up
            VOLUME_DOWN -> R.string.settings_swc_vol_down
            MUTE -> R.string.settings_swc_mute
            MAP_VIEW -> R.string.settings_swc_src
        },
    )
}

/**
 * Learned Android key codes. Stored as `ACTION=k:87`, joined by `;`.
 * One key code belongs to one action. This map does not use the head unit's key-test names.
 */
object GenericSwcMap {
    fun encode(bindings: Map<GenericSwcAction, Int>): String =
        GenericSwcAction.values().mapNotNull { action ->
            val keyCode = bindings[action]?.takeIf { it > 0 } ?: return@mapNotNull null
            "${action.name}=k:$keyCode"
        }.joinToString(";")

    fun decode(raw: String?): Map<GenericSwcAction, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<GenericSwcAction, Int>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val action = runCatching { GenericSwcAction.valueOf(part.substring(0, eq)) }.getOrNull() ?: continue
            val token = part.substring(eq + 1)
            val keyCode = token.takeIf { it.startsWith("k:") }?.substring(2)?.toIntOrNull()?.takeIf { it > 0 } ?: continue
            out.entries.removeAll { it.value == keyCode }
            out[action] = keyCode
        }
        return out
    }

    fun assign(
        bindings: Map<GenericSwcAction, Int>,
        action: GenericSwcAction,
        keyCode: Int,
    ): Map<GenericSwcAction, Int> {
        if (keyCode <= 0) return bindings
        val kept = bindings.filterValues { it != keyCode }
        return kept + (action to keyCode)
    }

    fun actionForKeyCode(bindings: Map<GenericSwcAction, Int>, keyCode: Int): GenericSwcAction? =
        bindings.entries.firstOrNull { it.value == keyCode }?.key
}
