package com.shihab.diplay.ui

import android.content.Context

/**
 * 车载 HMI 尺寸令牌（目标 **12.3″ 1920×720** SoftAP 车机）。
 *
 * 短屏/超宽按驾驶舱：左侧窄导航 + 顶栏（页名 / 状态 / 时钟），竖屏改成底栏导航。
 * 1920×720 三列并排、可点行至少 48dp，放不下的设置列自己滚动。其它短屏仍用大触控行。
 * 面板与强调色由 [GlassColors] 负责：底图上的玻璃，系统蓝只留给主操作和选中。
 *
 * Dimension getters return **px**（已乘 density）。Text-size getters return **sp**。
 */
object LayoutMetrics {
    private const val SHORT_HEIGHT_DP = 560

    /**
     * 横屏且够宽（车机 1920×720）才并排。竖屏平板改成上内容、下导航，避免三列被挤成手机设置。
     */
    fun isWide(context: Context): Boolean {
        val cfg = context.resources.configuration
        val w = cfg.screenWidthDp
        val h = cfg.screenHeightDp
        if (w > 0 && h > 0) return w >= h && w >= 720
        val dm = context.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels && dm.widthPixels >= 1200
    }

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

    /**
     * 12.3″ 1920×720 @160dpi：宽且矮。这一档把行高和字号收进一屏，
     * 而不是沿用短屏的大触控尺寸。
     */
    fun isCockpit(context: Context): Boolean = isWide(context) && isShort(context)

    private fun <T> tier(context: Context, cockpit: T, short: T, regular: T): T = when {
        isCockpit(context) -> cockpit
        isShort(context) -> short
        else -> regular
    }

    private fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    // ---- 页 / 卡 ----
    fun pagePad(context: Context): Int = px(context, tier(context, 8, 18, 20))
    fun cardPadH(context: Context): Int = px(context, tier(context, 12, 20, 22))
    fun cardPadV(context: Context): Int = px(context, tier(context, 6, 16, 20))
    fun colGap(context: Context): Int = px(context, tier(context, 8, 12, 16))
    fun headerBottom(context: Context): Int = px(context, tier(context, 4, 8, 16))
    /** 行上下内边距：车载触控空隙。驾驶舱收紧，好让整列落在 720px 里。 */
    fun rowPadV(context: Context): Int = px(context, tier(context, 2, 12, 10))
    /** 行最小高度。驾驶舱 48dp，点得到；列超出后滚动。 */
    fun rowMinH(context: Context): Int = px(context, tier(context, 48, 64, 56))
    fun cardTitleGap(context: Context): Int = px(context, tier(context, 4, 12, 12))
    fun dividerGap(context: Context): Int = px(context, tier(context, 4, 12, 14))

    fun settingsRowTextSize(context: Context): Float = tier(context, 15f, 20f, 16f)
    fun settingsCapTextSize(context: Context): Float = tier(context, 13f, 16f, 14f)
    fun capPadH(context: Context): Int = px(context, tier(context, 8, 16, 12))
    /** 含 font padding 的垂直内边距，避免汉字底被裁。 */
    fun capPadV(context: Context): Int = px(context, tier(context, 2, 6, 4))
    /** 值胶囊和次级按钮的高度。按字高收，不再用 48dp 触控行。 */
    fun capMinH(context: Context): Int = px(context, tier(context, 32, 36, 32))

    fun ghostTextSize(context: Context): Float = tier(context, 13f, 17f, 14f)
    fun ghostPadH(context: Context): Int = px(context, tier(context, 10, 18, 14))
    fun ghostPadV(context: Context): Int = px(context, tier(context, 2, 6, 4))

    /** 开关（dp 数值，调用方再 dp()）。 */
    fun switchW(context: Context): Int = tier(context, 52, 64, 48)
    fun switchH(context: Context): Int = tier(context, 28, 38, 28)
    fun switchKnob(context: Context): Int = tier(context, 22, 30, 22)

    fun appIconDp(context: Context): Int = tier(context, 44, 72, 56)
    fun appLabelSize(context: Context): Float = tier(context, 12f, 14f, 12f)
    fun appTilePadH(context: Context): Int = px(context, tier(context, 4, 8, 8))
    fun appTilePadT(context: Context): Int = px(context, tier(context, 4, 14, 14))
    fun appTilePadB(context: Context): Int = px(context, tier(context, 2, 10, 10))
    fun appTileMargin(context: Context): Int = px(context, tier(context, 2, 6, 6))
    /** 驾驶舱用 4 列，少占高度。短屏仍 3 列大图标。 */
    fun appColumns(context: Context): Int = tier(context, 4, 3, 4)

    // ---- 左侧窄导航 / 顶栏 ----
    /** 贴左的窄轨。宽度够放下选中胶囊和两个字的短标签。 */
    fun navWidth(context: Context): Int = px(context, tier(context, 84, 92, 100))
    fun statusBarH(context: Context): Int = px(context, tier(context, 44, 56, 60))
    fun statusClockSize(context: Context): Float = tier(context, 20f, 28f, 26f)
    fun statusDateSize(context: Context): Float = tier(context, 12f, 15f, 14f)
    fun navEntryMinH(context: Context): Int = px(context, tier(context, 52, 64, 60))
    fun navIconSize(context: Context): Int = px(context, tier(context, 20, 22, 22))
    fun navLogoSize(context: Context): Int = px(context, tier(context, 28, 30, 32))
    fun navVersionSize(context: Context): Float = 10f

    // ---- 主页 Hero / 分段 Tab ----
    /** 主页主按钮高度。连接球另算。 */
    fun heroMinH(context: Context): Int = px(context, tier(context, 32, 40, 36))
    /** 主页圆形连接按钮边长，含外围进度环。 */
    fun connectOrbSize(context: Context): Int = px(context, tier(context, 128, 168, 152))
    /** 连接按钮下五步玻璃槽的高度，含圆点和短标签。 */
    fun connectFlowH(context: Context): Int = px(context, tier(context, 48, 64, 56))
    fun heroPad(context: Context): Int = px(context, tier(context, 8, 18, 18))
    fun heroGap(context: Context): Int = px(context, tier(context, 6, 12, 14))
    fun heroSubSize(context: Context): Float = tier(context, 12f, 14f, 13f)
    fun heroRadiusDp(context: Context): Int = 22

    fun modeTabTextSize(context: Context): Float = tier(context, 14f, 17f, 14f)
    fun modeTabPadV(context: Context): Int = px(context, tier(context, 2, 6, 4))
    fun modeTabRadiusDp(context: Context): Int = 20
    fun modeTabMinH(context: Context): Int = px(context, tier(context, 32, 40, 36))

    fun statusPillTextSize(context: Context): Float = tier(context, 12f, 14f, 13f)
    fun statusPillPadH(context: Context): Int = px(context, tier(context, 8, 12, 11))
    fun statusPillPadV(context: Context): Int = px(context, tier(context, 2, 6, 4))
    fun homeBodyTextSize(context: Context): Float = tier(context, 14f, 18f, 15f)
    fun homeMutedTextSize(context: Context): Float = tier(context, 12f, 16f, 14f)
    fun manageBtnTextSize(context: Context): Float = tier(context, 13f, 16f, 15f)
    fun manageBtnMinW(context: Context): Int = px(context, tier(context, 56, 72, 64))
    fun manageBtnMinH(context: Context): Int = px(context, tier(context, 32, 36, 32))
    fun disconnectBtnH(context: Context): Int = px(context, tier(context, 32, 40, 36))
    fun homeWifiListH(context: Context): Int = px(context, tier(context, 112, 160, 240))

    // ---- 字号 ----
    fun headerTitleSize(context: Context): Float = tier(context, 18f, 22f, 24f)
    fun headerSubtitleSize(context: Context): Float = tier(context, 12f, 15f, 14f)
    fun showHeaderSubtitle(context: Context): Boolean = !isShort(context)
    fun cardTitleSize(context: Context): Float = tier(context, 16f, 22f, 20f)
    fun navLabelSize(context: Context): Float = 12f
    fun heroMainSize(context: Context): Float = tier(context, 18f, 24f, 22f)

    /**
     * 主页左右栏权重（连接 : 应用）。连接是主舞台，应用格退后，仍保持 3 列大图标。
     */
    fun homeConnectWeight(context: Context): Float = tier(context, 5.2f, 6.4f, 5.6f)
    fun homeAppsWeight(context: Context): Float = tier(context, 4.8f, 3.6f, 4.4f)
    /** 主页两栏之间的空隙，比设置列距更开。 */
    fun homeColumnGap(context: Context): Int = px(context, tier(context, 8, 20, 24))

    /** 竖屏主页底部应用条高度，只留一行大图标。 */
    fun homeAppsStripH(context: Context): Int = px(context, tier(context, 120, 148, 168))

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

    /**
     * 统一 dp→px 入口（四舍五入）。替代各文件本地重复的 dp()。
     */
    fun dp(context: Context, value: Int): Int = px(context, value)
}

/** 便捷扩展：在 Context（Activity / View 上下文）内直接 dp(n)。 */
fun Context.dp(value: Int): Int = LayoutMetrics.dp(this, value)
