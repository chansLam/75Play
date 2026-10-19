package com.shihab.diplay.ui

import android.content.Context

/**
 * 车载 HMI 尺寸令牌（目标 **12.3″ 1920×720** SoftAP 车机）。
 *
 * 短屏/超宽一律按「驾驶触控」：≥56dp 触控、18sp+ 正文、少列大图标。
 * 玻璃材质仍由 [GlassColors] / drawable 负责，本类只管尺度与间距。
 *
 * Dimension getters return **px**（已乘 density）。Text-size getters return **sp**。
 */
object LayoutMetrics {
    private const val SHORT_HEIGHT_DP = 560

    fun isShort(context: Context): Boolean {
        val cfg = context.resources.configuration
        val hDp = cfg.screenHeightDp
        if (hDp in 1 until SHORT_HEIGHT_DP) return true
        val dm = context.resources.displayMetrics
        if (dm.heightPixels in 1 until 800) return true
        val wDp = cfg.screenWidthDp
        if (wDp > 0 && hDp > 0 && wDp >= hDp * 2) return true
        return false
    }

    private fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    // ---- 页 / 卡 ----
    fun pagePad(context: Context): Int = px(context, if (isShort(context)) 12 else 24)
    fun cardPadH(context: Context): Int = px(context, if (isShort(context)) 20 else 22)
    fun cardPadV(context: Context): Int = px(context, if (isShort(context)) 16 else 20)
    fun colGap(context: Context): Int = px(context, if (isShort(context)) 12 else 16)
    fun headerBottom(context: Context): Int = px(context, if (isShort(context)) 8 else 16)
    /** 行上下内边距：车载触控空隙。 */
    fun rowPadV(context: Context): Int = px(context, if (isShort(context)) 12 else 10)
    /** 行最小高度：车载 ≥56dp。 */
    fun rowMinH(context: Context): Int = px(context, if (isShort(context)) 60 else 48)
    fun cardTitleGap(context: Context): Int = px(context, if (isShort(context)) 12 else 12)
    fun dividerGap(context: Context): Int = px(context, if (isShort(context)) 12 else 14)

    fun settingsRowTextSize(context: Context): Float = if (isShort(context)) 20f else 16f
    fun settingsCapTextSize(context: Context): Float = if (isShort(context)) 16f else 14f
    fun capPadH(context: Context): Int = px(context, if (isShort(context)) 16 else 12)
    /** 含 font padding 的垂直内边距，避免汉字底被裁。 */
    fun capPadV(context: Context): Int = px(context, if (isShort(context)) 10 else 6)
    fun capMinH(context: Context): Int = px(context, if (isShort(context)) 40 else 34)

    fun ghostTextSize(context: Context): Float = if (isShort(context)) 17f else 14f
    fun ghostPadH(context: Context): Int = px(context, if (isShort(context)) 18 else 14)
    fun ghostPadV(context: Context): Int = px(context, if (isShort(context)) 12 else 8)

    /** 开关（dp 数值，调用方再 dp()）。 */
    fun switchW(context: Context): Int = if (isShort(context)) 64 else 48
    fun switchH(context: Context): Int = if (isShort(context)) 38 else 28
    fun switchKnob(context: Context): Int = if (isShort(context)) 30 else 22

    fun appIconDp(context: Context): Int = if (isShort(context)) 72 else 56
    fun appLabelSize(context: Context): Float = if (isShort(context)) 14f else 12f
    fun appTilePadH(context: Context): Int = px(context, if (isShort(context)) 8 else 8)
    fun appTilePadT(context: Context): Int = px(context, if (isShort(context)) 14 else 14)
    fun appTilePadB(context: Context): Int = px(context, if (isShort(context)) 10 else 10)
    fun appTileMargin(context: Context): Int = px(context, if (isShort(context)) 6 else 6)
    /** 车机少列放大格子：短屏 3 列。 */
    fun appColumns(context: Context): Int = if (isShort(context)) 3 else 4

    // ---- 侧栏 ----
    fun navWidth(context: Context): Int = px(context, if (isShort(context)) 128 else 132)
    fun navPadV(context: Context): Int = px(context, if (isShort(context)) 14 else 16)
    fun navPadH(context: Context): Int = px(context, if (isShort(context)) 10 else 14)
    fun navEntryMinH(context: Context): Int = px(context, if (isShort(context)) 88 else 72)
    fun navLogoSize(context: Context): Int = px(context, if (isShort(context)) 52 else 48)
    fun navIconSize(context: Context): Int = px(context, if (isShort(context)) 34 else 28)
    fun navVersionSize(context: Context): Float = if (isShort(context)) 12f else 11f

    // ---- 主页 Hero / 分段 Tab ----
    fun heroPad(context: Context): Int = px(context, if (isShort(context)) 18 else 18)
    fun heroGap(context: Context): Int = px(context, if (isShort(context)) 12 else 14)
    fun heroPlaySize(context: Context): Float = if (isShort(context)) 18f else 17f
    fun heroSubSize(context: Context): Float = if (isShort(context)) 14f else 13f
    fun showHeroSubtitle(context: Context): Boolean = true
    fun heroRadiusDp(context: Context): Int = if (isShort(context)) 16 else 20

    fun modeTabTextSize(context: Context): Float = if (isShort(context)) 17f else 14f
    fun modeTabPadV(context: Context): Int = px(context, if (isShort(context)) 14 else 11)
    fun modeTabRadiusDp(context: Context): Int = if (isShort(context)) 14 else 12
    fun modeTabMinH(context: Context): Int = px(context, if (isShort(context)) 56 else 44)

    fun statusPillTextSize(context: Context): Float = if (isShort(context)) 14f else 13f
    fun statusPillPadH(context: Context): Int = px(context, if (isShort(context)) 12 else 11)
    fun statusPillPadV(context: Context): Int = px(context, if (isShort(context)) 6 else 4)
    fun homeBodyTextSize(context: Context): Float = if (isShort(context)) 18f else 15f
    fun homeMutedTextSize(context: Context): Float = if (isShort(context)) 16f else 14f
    fun manageBtnTextSize(context: Context): Float = if (isShort(context)) 16f else 15f
    fun manageBtnMinW(context: Context): Int = px(context, if (isShort(context)) 72 else 64)
    fun manageBtnMinH(context: Context): Int = px(context, if (isShort(context)) 44 else 40)
    fun disconnectBtnH(context: Context): Int = px(context, if (isShort(context)) 52 else 48)
    fun homeWifiListH(context: Context): Int = px(context, if (isShort(context)) 160 else 240)

    // ---- 字号 ----
    fun headerTitleSize(context: Context): Float = if (isShort(context)) 22f else 24f
    fun headerSubtitleSize(context: Context): Float = if (isShort(context)) 15f else 14f
    fun showHeaderSubtitle(context: Context): Boolean = !isShort(context)
    fun cardTitleSize(context: Context): Float = if (isShort(context)) 22f else 20f
    fun navLabelSize(context: Context): Float = if (isShort(context)) 15f else 13f
    fun navBrandSize(context: Context): Float = if (isShort(context)) 15f else 14f
    fun heroMainSize(context: Context): Float = if (isShort(context)) 24f else 22f

    /** 主页左右栏权重（连接 : 应用）。短屏略偏连接，主操作更醒目。 */
    fun homeConnectWeight(context: Context): Float = if (isShort(context)) 5.5f else 5f
    fun homeAppsWeight(context: Context): Float = if (isShort(context)) 5.5f else 6f

    enum class DialogSize { NARROW, STANDARD, WIDE }

    fun dialogWidthPx(context: Context, size: DialogSize = DialogSize.STANDARD): Int {
        val maxDp = when (size) {
            DialogSize.NARROW -> if (isShort(context)) 360 else 320
            DialogSize.STANDARD -> if (isShort(context)) 520 else 448
            DialogSize.WIDE -> if (isShort(context)) 640 else 560
        }
        val dm = context.resources.displayMetrics
        val side = px(context, 24)
        val screenCap = (dm.widthPixels - side * 2).coerceAtLeast(px(context, 280))
        return px(context, maxDp).coerceAtMost(screenCap)
    }

    fun dialogMaxHeightPx(context: Context): Int {
        val h = context.resources.displayMetrics.heightPixels
        val inset = pagePad(context) + px(context, 4)
        return (h - inset * 2).coerceAtLeast(px(context, 200))
    }
}
