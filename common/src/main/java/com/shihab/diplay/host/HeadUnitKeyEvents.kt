package com.shihab.diplay.host

import android.content.Intent

/** One press or release taken from a head-unit steering broadcast. */
internal data class HeadUnitKeyPress(
    val token: String,
    val pressed: Boolean,
    val edgeOnly: Boolean = false,
)

/**
 * Steering keys on this Freescale / Coagent head unit are broadcasts, not Android [android.view.KeyEvent]s.
 * The actions and extras match the wheel listener used by the picture-in-picture app that already
 * sees these keys: Coagent `KEY_CHANGED`, plus the other MCU broadcasts that app registers.
 */
internal object HeadUnitKeyEvents {
    const val ACTION_KEY_CHANGED = "com.coagent.intent.action.KEY_CHANGED"
    const val ACTION_KEY_TEST = "com.coagent.intent.action.KEY_CHANGED_TEST_MODE"
    const val EXTRA_CODE = "Key_code"
    const val EXTRA_STATE = "Key_state"
    const val KEY_TEST_SETTING = "coagent_settings_key_test_mode"

    private const val ACTION_C3 = "android.intent.action.C3_HARDKEY"
    private const val EXTRA_C3_CODE = "android.intent.extra.c3_hardkey_keycode"
    private const val EXTRA_C3_ACTION = "android.intent.extra.c3_hardkey_action"
    private const val ACTION_OKL = "com.okl.android.MCU_KEY_IND_ACTION"
    private const val ACTION_ADAYO = "com.adayo.android.MCU_KEY_IND_ACTION"
    private const val ACTION_NWD = "com.nwd.action.ACTION_KEY_VALUE"
    private const val ACTION_SPD = "com.spd.system.key"
    private const val ACTION_CLICK = "IKeyClick.KEY_CLICK"

    /** Broadcasts the learn dialog listens for. Key-test mode is not required. */
    fun learnActions(): List<String> = listOf(
        ACTION_KEY_CHANGED,
        ACTION_KEY_TEST,
        ACTION_C3,
        ACTION_OKL,
        ACTION_ADAYO,
        ACTION_NWD,
        ACTION_SPD,
        ACTION_CLICK,
    )

    fun parse(intent: Intent): HeadUnitKeyPress? = parseFields(
        intent.action,
        intent::getStringExtra,
        intent::getIntExtra,
    )

    internal fun parseFields(
        action: String?,
        stringExtra: (String) -> String?,
        intExtra: (String, Int) -> Int,
    ): HeadUnitKeyPress? = when (action) {
        ACTION_KEY_CHANGED, ACTION_KEY_TEST -> coagent(stringExtra)
        ACTION_C3 -> numbered("c3", intExtra(EXTRA_C3_CODE, -1), intExtra(EXTRA_C3_ACTION, 0))
        ACTION_OKL, ACTION_ADAYO -> mcu(stringExtra, intExtra)
        ACTION_NWD -> namedOrNumber("nwd", stringExtra("value") ?: stringExtra("extra_key_value"), intExtra("value", -1), stringExtra("state"), intExtra("state", 0))
        ACTION_SPD -> intExtra("extra_key_value", -1).takeIf { it >= 0 }?.let {
            HeadUnitKeyPress("spd:$it", pressed = true, edgeOnly = true)
        }
        ACTION_CLICK -> numbered("click", intExtra("CLICK_KEY", -1), intExtra("STATUS_KEY", 0))
        else -> null
    }

    private fun coagent(stringExtra: (String) -> String?): HeadUnitKeyPress? {
        val name = stringExtra(EXTRA_CODE)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val state = stringExtra(EXTRA_STATE)?.trim()?.uppercase()
        val pressed = when (state) {
            null, "", "DOWN", "NONE" -> true
            "UP", "LONG", "LONG_UP" -> false
            else -> return null
        }
        return HeadUnitKeyPress(name, pressed)
    }

    /** `key_value` / `key_state`. PRESS_DOWN and NONE are a press; UP is a release. */
    private fun mcu(stringExtra: (String) -> String?, intExtra: (String, Int) -> Int): HeadUnitKeyPress? {
        val number = intExtra("key_value", -1)
        val text = stringExtra("key_value")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val token = when {
            number >= 0 -> "mcu:$number"
            text != null -> "mcu:$text"
            else -> return null
        }
        val state = stringExtra("key_state")?.trim()?.uppercase()
        val pressed = when (state) {
            "PRESS_DOWN", "DOWN", "NONE" -> true
            "UP", "LONG", "LONG_UP" -> false
            null, "" -> when (intExtra("key_state", 0)) {
                0 -> true
                1, 2 -> false
                else -> return null
            }
            else -> return null
        }
        return HeadUnitKeyPress(token, pressed)
    }

    /** Integer key plus an action. 0 is down, 1 and 2 are up, matching the Coagent 0/1/2 states. */
    private fun numbered(prefix: String, code: Int, action: Int): HeadUnitKeyPress? {
        if (code < 0) return null
        if (action == 2 || action == 1) return HeadUnitKeyPress("$prefix:$code", pressed = false)
        return HeadUnitKeyPress("$prefix:$code", pressed = true)
    }

    private fun namedOrNumber(
        prefix: String,
        text: String?,
        number: Int,
        stateText: String?,
        stateNumber: Int,
    ): HeadUnitKeyPress? {
        val token = text?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { "$prefix:$it" }
            ?: number.takeIf { it >= 0 }?.let { "$prefix:$it" }
            ?: return null
        val pressed = when (stateText?.trim()?.uppercase()) {
            "UP", "LONG", "LONG_UP" -> false
            "DOWN", "NONE", "PRESS_DOWN" -> true
            null, "" -> stateNumber != 2 && stateNumber != 1
            else -> return null
        }
        return HeadUnitKeyPress(token, pressed)
    }
}
