package com.shihab.diplay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ShellDesignTokensTest {
    @Test fun `spacing follows the four dp grid`() {
        assertTrue(ShellSpacing.scale.all { it % 4 == 0 })
        assertTrue(ShellSpacing.MIN_TOUCH_TARGET >= 48)
    }

    @Test fun `shape scale has exactly five roles`() {
        assertEquals(5, ShellShape.scale.size)
        assertEquals(ShellShape.scale.sorted(), ShellShape.scale)
    }

    @Test fun `type scale uses no more than seven sizes`() {
        assertTrue(ShellType.scale.distinct().size <= 7)
    }

    @Test fun `motion stays within the functional duration budget`() {
        assertTrue(ShellMotion.durations.all { it in 1L..600L })
    }

    @Test fun `light and dark role pairs meet text contrast`() {
        listOf(ShellColors.Light, ShellColors.Dark).forEach { colors ->
            val pairs = listOf(
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceVariant,
                colors.inverseOnSurface to colors.inverseSurface,
                colors.onError to colors.error,
                colors.onErrorContainer to colors.errorContainer,
                colors.onWarning to colors.warning,
                colors.onWarningContainer to colors.warningContainer,
                colors.onSuccess to colors.success,
                colors.onSuccessContainer to colors.successContainer,
            )
            pairs.forEach { (foreground, background) ->
                assertTrue(
                    "contrast ${hex(foreground)} on ${hex(background)}",
                    contrast(foreground, background) >= 4.5,
                )
            }
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 0xff) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun hex(color: Int): String = "#%08X".format(color)
}
