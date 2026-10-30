package com.shihab.diplay.model

/**
 * Deployment hotspot topology chosen at runtime. Shared by the runtime config, the network
 * bring-up layer and the settings UI; lives here to keep [com.shihab.diplay.network] from
 * importing [com.shihab.diplay.orchestration].
 */
enum class WirelessHotspotMode {
    WIFI_P2P,
    MANUAL,
    EXISTING_WIFI,
}

/** Band selection for a manually-configured SoftAP. */
enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

/** Security mode advertised for a manually-configured SoftAP. */
enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}
