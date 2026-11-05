package com.shihab.diplay.host

import android.view.KeyEvent

/**
 * Captures the next Android key code while the shell learn dialog is waiting.
 * The head unit's key-test broadcast is not used here.
 */
object GenericSwcLearn {
    private var sink: ((Int) -> Unit)? = null
    private var armed: GenericSwcAction? = null
    private var onDisarm: (() -> Unit)? = null

    fun isArmed(): Boolean = sink != null

    fun armedAction(): GenericSwcAction? = armed

    fun close() {
        sink = null
        armed = null
        onDisarm = null
    }

    fun setOnDisarm(block: (() -> Unit)?) {
        onDisarm = block
    }

    fun arm(action: GenericSwcAction, onCode: (Int) -> Unit) {
        armed = action
        sink = onCode
    }

    /** Drops the pending learn. True when a learn was pending. */
    fun disarm(): Boolean {
        if (sink == null && armed == null) return false
        sink = null
        armed = null
        onDisarm?.invoke()
        return true
    }

    /**
     * Consumes keys while a function is waiting. Back is left to the caller so the first
     * press cancels the learn and a later press closes the dialog.
     */
    fun onKey(event: KeyEvent): Boolean {
        val current = sink ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) return false
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        current(event.keyCode)
        return true
    }
}
