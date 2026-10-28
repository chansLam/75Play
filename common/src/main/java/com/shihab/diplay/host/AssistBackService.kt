package com.shihab.diplay.host

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service whose only job is a global Back action for the floating ball.
 * It listens to nothing and cannot read window content; the user enables it manually in
 * system accessibility settings (KitKat has no programmatic way to enable it).
 */
class AssistBackService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        private var instance: AssistBackService? = null

        /** True once the system has bound the service (user enabled it in accessibility settings). */
        val isReady: Boolean get() = instance != null

        /** Performs GLOBAL_ACTION_BACK; false when the service is not connected. */
        fun performBack(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
    }
}
