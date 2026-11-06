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

/** A learned steering key: an Android key code, or a name from the head unit's key broadcast. */
sealed class GenericSwcCode {
    data class Android(val keyCode: Int) : GenericSwcCode()
    data class Name(val name: String) : GenericSwcCode()

    fun label(): String = when (this) {
        is Android -> android.view.KeyEvent.keyCodeToString(keyCode)
        is Name -> name
    }
}

/**
 * Learned keys. Stored as `ACTION=k:87` or `ACTION=n:VOLUP`, joined by `;`.
 * One code belongs to one action. Names come from the head unit broadcast
 * (`Key_code` and the other wheel broadcasts), not from key-test mode's fixed table.
 */
object GenericSwcMap {
    fun encode(bindings: Map<GenericSwcAction, GenericSwcCode>): String =
        GenericSwcAction.values().mapNotNull { action ->
            val token = token(bindings[action] ?: return@mapNotNull null) ?: return@mapNotNull null
            "${action.name}=$token"
        }.joinToString(";")

    fun decode(raw: String?): Map<GenericSwcAction, GenericSwcCode> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<GenericSwcAction, GenericSwcCode>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val action = runCatching { GenericSwcAction.valueOf(part.substring(0, eq)) }.getOrNull() ?: continue
            val code = code(part.substring(eq + 1)) ?: continue
            out.entries.removeAll { same(it.value, code) }
            out[action] = code
        }
        return out
    }

    fun assign(
        bindings: Map<GenericSwcAction, GenericSwcCode>,
        action: GenericSwcAction,
        code: GenericSwcCode,
    ): Map<GenericSwcAction, GenericSwcCode> {
        if (token(code) == null) return bindings
        val kept = bindings.filterValues { !same(it, code) }
        return kept + (action to code)
    }

    fun actionForKeyCode(bindings: Map<GenericSwcAction, GenericSwcCode>, keyCode: Int): GenericSwcAction? =
        bindings.entries.firstOrNull { (it.value as? GenericSwcCode.Android)?.keyCode == keyCode }?.key

    fun actionForName(bindings: Map<GenericSwcAction, GenericSwcCode>, name: String): GenericSwcAction? =
        bindings.entries.firstOrNull { code ->
            val learned = code.value as? GenericSwcCode.Name ?: return@firstOrNull false
            learned.name.equals(name, ignoreCase = true)
        }?.key

    private fun token(code: GenericSwcCode?): String? = when (code) {
        is GenericSwcCode.Android -> code.keyCode.takeIf { it > 0 }?.let { "k:$it" }
        is GenericSwcCode.Name -> code.name.trim().uppercase().takeIf { it.isNotEmpty() && !it.contains(';') && !it.contains('=') }
            ?.let { "n:$it" }
        null -> null
    }

    private fun code(token: String): GenericSwcCode? = when {
        token.startsWith("k:") -> token.substring(2).toIntOrNull()?.takeIf { it > 0 }?.let { GenericSwcCode.Android(it) }
        token.startsWith("n:") -> token.substring(2).trim().uppercase().takeIf { it.isNotEmpty() }?.let { GenericSwcCode.Name(it) }
        else -> null
    }

    private fun same(left: GenericSwcCode, right: GenericSwcCode): Boolean = token(left) == token(right)
}
