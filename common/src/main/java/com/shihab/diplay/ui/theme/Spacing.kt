package com.shihab.diplay.ui.theme

/** Liquid-glass spacing roles in density-independent pixels. */
object ShellSpacing {
    const val EXTRA_SMALL = 4
    const val SMALL = 8
    const val MEDIUM = 12
    const val LARGE = 16
    const val EXTRA_LARGE = 20
    const val DOUBLE_EXTRA_LARGE = 24
    const val TRIPLE_EXTRA_LARGE = 32
    const val SECTION = 40
    const val SECTION_LARGE = 56
    const val MIN_TOUCH_TARGET = 48

    val scale: List<Int> = listOf(
        EXTRA_SMALL,
        SMALL,
        MEDIUM,
        LARGE,
        EXTRA_LARGE,
        DOUBLE_EXTRA_LARGE,
        TRIPLE_EXTRA_LARGE,
        SECTION,
        SECTION_LARGE,
    )
}

/** Fixed shell geometry for the 1920x720 landscape rail layout and its compact fallbacks. */
object ShellComponentSize {
    /**
     * The CS75 firmware reports a phone-like density for a physically 1920x720 cockpit panel.
     * Cockpit geometry therefore maps one design unit to one physical pixel; otherwise a 48dp
     * row becomes roughly 72px tall and the real head unit loses several rows versus the AVD.
     */
    const val COCKPIT_PX_PER_DP = 1f
    const val COCKPIT_HEIGHT_DP = 560
    const val WIDE_WIDTH_DP = 720
    const val WIDE_WIDTH_PX_FALLBACK = 1200
    const val SHORT_HEIGHT_PX_FALLBACK = 800

    const val NAV_WIDTH_COMPACT = 84
    const val NAV_WIDTH_SHORT = 92
    const val NAV_WIDTH_REGULAR = 100
    const val STATUS_BAR_COMPACT = 44
    const val STATUS_BAR_SHORT = 56
    const val STATUS_BAR_REGULAR = 60
    const val NAV_ENTRY_COMPACT = 52
    const val NAV_ENTRY_SHORT = 64
    const val NAV_ENTRY_REGULAR = 60
    const val ICON_SMALL = 20
    const val ICON_MEDIUM = 24
    const val LOGO_COMPACT = 28
    const val LOGO_SHORT = 32
    const val LOGO_REGULAR = 32

    const val APP_ICON_COMPACT = 52
    const val APP_ICON_SHORT = 72
    const val APP_ICON_REGULAR = 64
    const val CONNECT_ORB_COMPACT = 144
    const val CONNECT_ORB_SHORT = 168
    const val CONNECT_ORB_REGULAR = 152
    const val CONNECT_FLOW_COMPACT = 48
    const val CONNECT_FLOW_SHORT = 64
    const val CONNECT_FLOW_REGULAR = 56
    const val WIFI_LIST_COMPACT = 112
    const val WIFI_LIST_SHORT = 160
    const val WIFI_LIST_REGULAR = 240
    const val APP_STRIP_COMPACT = 120
    const val APP_STRIP_SHORT = 148
    const val APP_STRIP_REGULAR = 168

    const val SWITCH_WIDTH_COMPACT = 52
    const val SWITCH_WIDTH_SHORT = 64
    const val SWITCH_WIDTH_REGULAR = 48
    const val SWITCH_TRACK_COMPACT = 28
    const val SWITCH_TRACK_SHORT = 40
    const val SWITCH_TRACK_REGULAR = 28
    const val SWITCH_KNOB_COMPACT = 24
    const val SWITCH_KNOB_SHORT = 32
    const val SWITCH_KNOB_REGULAR = 24

    const val DIALOG_NARROW_SHORT = 360
    const val DIALOG_NARROW_REGULAR = 320
    const val DIALOG_STANDARD_SHORT = 520
    const val DIALOG_STANDARD_REGULAR = 448
    const val DIALOG_WIDE_SHORT = 640
    const val DIALOG_WIDE_REGULAR = 560
    const val DIALOG_SCREEN_MIN = 280
    const val DIALOG_HEIGHT_MIN = 200
    const val DROPDOWN_MIN_WIDTH = 160
    const val TITLE_DIVIDER_WIDTH = 160
    const val SETTINGS_CATEGORY_WIDTH = 188
    const val SETTINGS_CATEGORY_HEIGHT = 64
    const val SETTINGS_CONTENT_WIDTH = 1080
    const val SETTINGS_CONTENT_MIN_WIDTH = 640
}
