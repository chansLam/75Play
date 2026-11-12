package com.shihab.diplay.ui.theme

/** Complete shell palette. Keep color literals in this file only. */
data class ShellColorScheme(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceTint: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val warning: Int,
    val onWarning: Int,
    val warningContainer: Int,
    val onWarningContainer: Int,
    val success: Int,
    val onSuccess: Int,
    val successContainer: Int,
    val onSuccessContainer: Int,
    val outline: Int,
    val outlineVariant: Int,
    val scrim: Int,
)

/** Bergside glassmorphism colors adapted to API 19 Views. Brand seed: #1856FF. */
object ShellColors {
    const val TRANSPARENT: Int = 0x00000000

    val Light = ShellColorScheme(
        primary = 0xFF1856FF.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xD9DCE4FF.toInt(),
        onPrimaryContainer = 0xFF00174A.toInt(),
        inversePrimary = 0xFFB7C4FF.toInt(),
        secondary = 0xFF3A344E.toInt(),
        onSecondary = 0xFFFFFFFF.toInt(),
        secondaryContainer = 0xFFD6E4F7.toInt(),
        onSecondaryContainer = 0xFF071D33.toInt(),
        tertiary = 0xFF006B5B.toInt(),
        onTertiary = 0xFFFFFFFF.toInt(),
        tertiaryContainer = 0xFF9EF2DC.toInt(),
        onTertiaryContainer = 0xFF00201A.toInt(),
        background = 0xFFF0F5FA.toInt(),
        onBackground = 0xFF17191C.toInt(),
        surface = 0xEAF8FBFF.toInt(),
        onSurface = 0xFF17191C.toInt(),
        surfaceVariant = 0xC4EDF3FA.toInt(),
        onSurfaceVariant = 0xFF3E454D.toInt(),
        surfaceTint = 0xFF1856FF.toInt(),
        inverseSurface = 0xFF2E3034.toInt(),
        inverseOnSurface = 0xFFF1F3F5.toInt(),
        surfaceContainerLowest = 0x70FFFFFF.toInt(),
        surfaceContainerLow = 0xA6F7FAFD.toInt(),
        surfaceContainer = 0xB8EEF4FA.toInt(),
        surfaceContainerHigh = 0xD4F8FBFF.toInt(),
        surfaceContainerHighest = 0xE8FFFFFF.toInt(),
        error = 0xFFEA2143.toInt(),
        onError = 0xFF190006.toInt(),
        errorContainer = 0xFFFFDAD6.toInt(),
        onErrorContainer = 0xFF410002.toInt(),
        warning = 0xFFE89558.toInt(),
        onWarning = 0xFF341300.toInt(),
        warningContainer = 0xFFFFE0B2.toInt(),
        onWarningContainer = 0xFF2D1600.toInt(),
        success = 0xFF07A958.toInt(),
        onSuccess = 0xFF001B0C.toInt(),
        successContainer = 0xFFB9F3CC.toInt(),
        onSuccessContainer = 0xFF00210D.toInt(),
        outline = 0xFF69727C.toInt(),
        outlineVariant = 0x8AFFFFFF.toInt(),
        scrim = 0x8F000000.toInt(),
    )

    val Dark = ShellColorScheme(
        primary = 0xFFB7C4FF.toInt(),
        onPrimary = 0xFF002780.toInt(),
        primaryContainer = 0xD1173EBD.toInt(),
        onPrimaryContainer = 0xFFDCE4FF.toInt(),
        inversePrimary = 0xFF1856FF.toInt(),
        secondary = 0xFFD4C8E8.toInt(),
        onSecondary = 0xFF302B42.toInt(),
        secondaryContainer = 0xFF49425D.toInt(),
        onSecondaryContainer = 0xFFEDE5FF.toInt(),
        tertiary = 0xFF79D7C1.toInt(),
        onTertiary = 0xFF00382F.toInt(),
        tertiaryContainer = 0xFF005143.toInt(),
        onTertiaryContainer = 0xFF9EF2DC.toInt(),
        background = 0xFF070B11.toInt(),
        onBackground = 0xFFF1F3F5.toInt(),
        surface = 0xCC0A111B.toInt(),
        onSurface = 0xFFF1F3F5.toInt(),
        surfaceVariant = 0xA8283442.toInt(),
        onSurfaceVariant = 0xFFC3C8D0.toInt(),
        surfaceTint = 0xFFB7C4FF.toInt(),
        inverseSurface = 0xFFE2E4E8.toInt(),
        inverseOnSurface = 0xFF2E3034.toInt(),
        surfaceContainerLowest = 0x66060A10.toInt(),
        surfaceContainerLow = 0x840A111B.toInt(),
        surfaceContainer = 0x96101A27.toInt(),
        surfaceContainerHigh = 0xB31A2635.toInt(),
        surfaceContainerHighest = 0xCC243244.toInt(),
        error = 0xFFFFB2BE.toInt(),
        onError = 0xFF680018.toInt(),
        errorContainer = 0xFF92002A.toInt(),
        onErrorContainer = 0xFFFFD9DF.toInt(),
        warning = 0xFFFFC49D.toInt(),
        onWarning = 0xFF502300.toInt(),
        warningContainer = 0xFF713500.toInt(),
        onWarningContainer = 0xFFFFDBC5.toInt(),
        success = 0xFF67E69B.toInt(),
        onSuccess = 0xFF00391D.toInt(),
        successContainer = 0xFF00522E.toInt(),
        onSuccessContainer = 0xFFA0F6BF.toInt(),
        outline = 0xFF929AA4.toInt(),
        outlineVariant = 0x5EFFFFFF.toInt(),
        scrim = 0xB3000000.toInt(),
    )

    fun forNight(night: Boolean): ShellColorScheme = if (night) Dark else Light

    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}

/** Fixed colors used only by the branded update dialog. */
object ShellUpdateColors {
    val CYAN: Int = 0xFF0ECDFA.toInt()
    val BLUE: Int = 0xFF0096F4.toInt()
    val WHITE: Int = 0xFFE0F5FF.toInt()
    val MUTED: Int = 0xFFA4BFDC.toInt()
    val FADED: Int = 0xFF6985A4.toInt()
    val DIVIDER: Int = 0xFF0F5278.toInt()
    val DARK: Int = 0xFF011125.toInt()
}
