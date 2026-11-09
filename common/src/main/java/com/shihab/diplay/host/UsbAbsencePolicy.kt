package com.shihab.diplay.host

/**
 * USB disappearance is a link loss only for a session that actually depends on the cable.
 *
 * Wireless preference stays on by default, and a plugged-in iPhone still takes the wired path.
 * Treating "wireless enabled" as "the phone is present" hides that unplug from the poll, which
 * is the only signal this head unit reliably delivers.
 */
internal fun shouldDropLinkAfterUsbLoss(
    usbWasPresent: Boolean,
    usbPresent: Boolean,
    wirelessPreferred: Boolean,
    sessionOrConnectActive: Boolean,
    wiredLinkActive: Boolean,
): Boolean {
    if (!usbWasPresent || usbPresent || !sessionOrConnectActive) return false
    if (!wirelessPreferred) return true
    return wiredLinkActive
}

/** A new attempt needs a cable only when wireless bring-up is not allowed. */
internal fun shouldStartRecovery(wirelessPreferred: Boolean, usbPresent: Boolean): Boolean =
    wirelessPreferred || usbPresent
