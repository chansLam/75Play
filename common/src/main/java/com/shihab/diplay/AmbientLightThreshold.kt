package com.shihab.diplay

/**
 * A single brightness threshold separates night from day when the AMBIENT mode is backed by the
 * head unit's MCU-reported screen brightness (0..255) instead of a raw lux sensor. The value is
 * kept in the `lux` field so the persistence / settings plumbing stays unchanged; the range is
 * now the 0..255 brightness scale the firmware reports. Lower brightness => night.
 */
data class AmbientLightThreshold(val lux: Int = DEFAULT_LUX) {
    init {
        require(isValid(lux)) { "Brightness threshold is outside the supported range" }
    }

    companion object {
        const val DEFAULT_LUX = 80
        const val MIN_LUX = 0
        const val MAX_LUX = 255

        fun isValid(lux: Int): Boolean = lux in MIN_LUX..MAX_LUX

        fun fromStored(lux: Int): AmbientLightThreshold =
            if (isValid(lux)) AmbientLightThreshold(lux) else AmbientLightThreshold()
    }
}
