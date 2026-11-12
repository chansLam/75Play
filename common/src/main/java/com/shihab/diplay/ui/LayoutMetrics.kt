package com.shihab.diplay.ui

import android.content.Context
import com.shihab.diplay.ui.theme.ShellComponentSize
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellSpacing
import com.shihab.diplay.ui.theme.ShellType

/**
 * 车载 HMI 尺寸令牌（目标 **12.3″ 1920×720** SoftAP 车机）。
 *
 * 短屏/超宽按驾驶舱：左侧窄导航 + 顶栏（页名 / 状态 / 时钟），竖屏改成底栏导航。
 * 1920×720 四列并排、可点行至少 48 物理像素，放不下的设置列自己滚动。其它短屏仍用大触控行。
 * Panel and emphasis roles come from [GlassColors]; primary is reserved for actions and selection.
 *
 * Dimension getters return **px**. Cockpit mode deliberately uses physical pixels because the
 * target firmware advertises a phone-like density for a fixed automotive panel. Text getters
 * return **sp** values corrected by the same rule.
 */
object LayoutMetrics {

    /**
     * 横屏且够宽（车机 1920×720）才并排。竖屏平板改成上内容、下导航，避免三列被挤成手机设置。
     */
    fun isWide(context: Context): Boolean {
        val cfg = context.resources.configuration
        val w = cfg.screenWidthDp
        val h = cfg.screenHeightDp
        val dm = context.resources.displayMetrics
        val landscape = when {
            w > 0 && h > 0 -> w >= h
            else -> dm.widthPixels > dm.heightPixels
        }
        return landscape && (
            w >= ShellComponentSize.WIDE_WIDTH_DP ||
                dm.widthPixels >= ShellComponentSize.WIDE_WIDTH_PX_FALLBACK
            )
    }

    fun isShort(context: Context): Boolean {
        val cfg = context.resources.configuration
        val hDp = cfg.screenHeightDp
        if (hDp in 1 until ShellComponentSize.COCKPIT_HEIGHT_DP) return true
        val dm = context.resources.displayMetrics
        if (dm.heightPixels in 1 until ShellComponentSize.SHORT_HEIGHT_PX_FALLBACK) return true
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

    private fun layoutDensity(context: Context): Float =
        if (isCockpit(context)) ShellComponentSize.COCKPIT_PX_PER_DP else context.resources.displayMetrics.density

    private fun px(context: Context, dp: Int): Int =
        (dp * layoutDensity(context) + 0.5f).toInt()

    private fun textSp(context: Context, sp: Float): Float = if (isCockpit(context)) {
        sp * ShellComponentSize.COCKPIT_PX_PER_DP /
            context.resources.displayMetrics.scaledDensity.coerceAtLeast(ShellComponentSize.COCKPIT_PX_PER_DP)
    } else {
        sp
    }

    // ---- 页 / 卡 ----
    fun pagePad(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.EXTRA_LARGE, ShellSpacing.EXTRA_LARGE))
    fun cardPadH(context: Context): Int = px(context, tier(context, ShellSpacing.MEDIUM, ShellSpacing.EXTRA_LARGE, ShellSpacing.DOUBLE_EXTRA_LARGE))
    fun cardPadV(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.LARGE, ShellSpacing.EXTRA_LARGE))
    fun colGap(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.MEDIUM, ShellSpacing.LARGE))
    fun headerBottom(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.SMALL, ShellSpacing.LARGE))
    /** 行上下内边距：车载触控空隙。驾驶舱收紧，好让整列落在 720px 里。 */
    fun rowPadV(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.MEDIUM, ShellSpacing.SMALL))
    /** 行最小高度。驾驶舱 48dp，点得到；列超出后滚动。 */
    fun rowMinH(context: Context): Int = px(context, tier(context, ShellSpacing.MIN_TOUCH_TARGET, ShellComponentSize.SWITCH_WIDTH_SHORT, ShellSpacing.SECTION_LARGE))
    fun cardTitleGap(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.MEDIUM, ShellSpacing.MEDIUM))
    fun dividerGap(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.MEDIUM, ShellSpacing.LARGE))

    fun settingsRowTextSize(context: Context): Float = textSp(context, tier(context, ShellType.LABEL, ShellType.TITLE, ShellType.BODY))
    fun settingsCapTextSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.BODY, ShellType.LABEL))
    fun capPadH(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.LARGE, ShellSpacing.MEDIUM))
    /** 含 font padding 的垂直内边距，避免汉字底被裁。 */
    fun capPadV(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.SMALL, ShellSpacing.EXTRA_SMALL))
    /** 值胶囊和次级按钮的高度。按字高收，不再用 48dp 触控行。 */
    fun capMinH(context: Context): Int = px(context, ShellSpacing.MIN_TOUCH_TARGET)

    fun wallpaperThumbPx(context: Context): Int = px(context, ShellComponentSize.WALLPAPER_THUMB)

    fun ghostTextSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.BODY, ShellType.LABEL))
    fun ghostPadH(context: Context): Int = px(context, tier(context, ShellSpacing.MEDIUM, ShellSpacing.EXTRA_LARGE, ShellSpacing.LARGE))
    fun ghostPadV(context: Context): Int = px(context, tier(context, ShellSpacing.EXTRA_SMALL, ShellSpacing.SMALL, ShellSpacing.EXTRA_SMALL))

    /** 开关（dp 数值，调用方再 dp()）。 */
    fun switchW(context: Context): Int = tier(context, ShellComponentSize.SWITCH_WIDTH_COMPACT, ShellComponentSize.SWITCH_WIDTH_SHORT, ShellComponentSize.SWITCH_WIDTH_REGULAR)
    fun switchH(context: Context): Int = tier(context, ShellComponentSize.SWITCH_TRACK_COMPACT, ShellComponentSize.SWITCH_TRACK_SHORT, ShellComponentSize.SWITCH_TRACK_REGULAR)
    fun switchKnob(context: Context): Int = tier(context, ShellComponentSize.SWITCH_KNOB_COMPACT, ShellComponentSize.SWITCH_KNOB_SHORT, ShellComponentSize.SWITCH_KNOB_REGULAR)

    fun appIconDp(context: Context): Int = tier(context, ShellComponentSize.APP_ICON_COMPACT, ShellComponentSize.APP_ICON_SHORT, ShellComponentSize.APP_ICON_REGULAR)
    fun appLabelSize(context: Context): Float = textSp(context, ShellType.CAPTION)
    fun appTilePadH(context: Context): Int = px(context, ShellSpacing.SMALL)
    fun appTilePadT(context: Context): Int = px(context, tier(context, ShellSpacing.MEDIUM, ShellSpacing.LARGE, ShellSpacing.MEDIUM))
    fun appTilePadB(context: Context): Int = px(context, ShellSpacing.SMALL)
    fun appTileMargin(context: Context): Int = px(context, ShellSpacing.SMALL)
    /** 驾驶舱固定四列，减少纵向滚动并贴近原车机的一屏信息密度。 */
    fun appColumns(context: Context): Int = tier(context, 4, 3, 4)

    // ---- 左侧窄导航 / 顶栏 ----
    /** 贴左的窄轨。宽度够放下选中胶囊和两个字的短标签。 */
    fun navWidth(context: Context): Int = px(context, tier(context, ShellComponentSize.NAV_WIDTH_COMPACT, ShellComponentSize.NAV_WIDTH_SHORT, ShellComponentSize.NAV_WIDTH_REGULAR))
    fun statusBarH(context: Context): Int = px(context, tier(context, ShellComponentSize.STATUS_BAR_COMPACT, ShellComponentSize.STATUS_BAR_SHORT, ShellComponentSize.STATUS_BAR_REGULAR))
    fun statusClockSize(context: Context): Float = textSp(context, tier(context, ShellType.TITLE, ShellType.DISPLAY, ShellType.HEADING))
    fun statusDateSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.LABEL, ShellType.LABEL))
    fun navEntryMinH(context: Context): Int = px(context, tier(context, ShellComponentSize.NAV_ENTRY_COMPACT, ShellComponentSize.NAV_ENTRY_SHORT, ShellComponentSize.NAV_ENTRY_REGULAR))
    fun navIconSize(context: Context): Int = px(context, tier(context, ShellComponentSize.ICON_SMALL, ShellComponentSize.ICON_MEDIUM, ShellComponentSize.ICON_MEDIUM))
    fun navLogoSize(context: Context): Int = px(context, tier(context, ShellComponentSize.LOGO_COMPACT, ShellComponentSize.LOGO_SHORT, ShellComponentSize.LOGO_REGULAR))
    fun navVersionSize(context: Context): Float = textSp(context, ShellType.CAPTION)

    // ---- 主页 Hero / 分段 Tab ----
    /** 主页主按钮高度。连接球另算。 */
    fun heroMinH(context: Context): Int = px(context, ShellSpacing.MIN_TOUCH_TARGET)
    /** 主页圆形连接按钮边长，含外围进度环。 */
    fun connectOrbSize(context: Context): Int = px(context, tier(context, ShellComponentSize.CONNECT_ORB_COMPACT, ShellComponentSize.CONNECT_ORB_SHORT, ShellComponentSize.CONNECT_ORB_REGULAR))
    /** Five-stage connection progress height, including nodes and labels. */
    fun connectFlowH(context: Context): Int = px(context, tier(context, ShellComponentSize.CONNECT_FLOW_COMPACT, ShellComponentSize.CONNECT_FLOW_SHORT, ShellComponentSize.CONNECT_FLOW_REGULAR))
    fun heroPad(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.EXTRA_LARGE, ShellSpacing.EXTRA_LARGE))
    fun heroGap(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.MEDIUM, ShellSpacing.LARGE))
    fun heroSubSize(context: Context): Float = textSp(context, ShellType.CAPTION)
    fun heroRadiusDp(context: Context): Int = ShellShape.LARGE

    fun modeTabTextSize(context: Context): Float = textSp(context, tier(context, ShellType.LABEL, ShellType.BODY, ShellType.LABEL))
    fun modeTabPadV(context: Context): Int = px(context, ShellSpacing.EXTRA_SMALL)
    fun modeTabRadiusDp(context: Context): Int = ShellShape.MEDIUM
    fun modeTabMinH(context: Context): Int = px(context, ShellSpacing.MIN_TOUCH_TARGET)

    fun statusPillTextSize(context: Context): Float = textSp(context, ShellType.CAPTION)
    fun statusPillPadH(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.MEDIUM, ShellSpacing.MEDIUM))
    fun statusPillPadV(context: Context): Int = px(context, ShellSpacing.EXTRA_SMALL)
    fun homeBodyTextSize(context: Context): Float = textSp(context, tier(context, ShellType.LABEL, ShellType.TITLE, ShellType.LABEL))
    fun homeMutedTextSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.BODY, ShellType.LABEL))
    fun manageBtnTextSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.BODY, ShellType.LABEL))
    fun manageBtnMinW(context: Context): Int = px(context, tier(context, ShellSpacing.SECTION_LARGE, ShellComponentSize.APP_ICON_SHORT, ShellComponentSize.APP_ICON_REGULAR))
    fun manageBtnMinH(context: Context): Int = px(context, ShellSpacing.MIN_TOUCH_TARGET)
    fun disconnectBtnH(context: Context): Int = px(context, ShellSpacing.MIN_TOUCH_TARGET)
    fun homeWifiListH(context: Context): Int = px(context, tier(context, ShellComponentSize.WIFI_LIST_COMPACT, ShellComponentSize.WIFI_LIST_SHORT, ShellComponentSize.WIFI_LIST_REGULAR))

    // ---- 字号 ----
    fun headerTitleSize(context: Context): Float = textSp(context, tier(context, ShellType.TITLE, ShellType.HEADING, ShellType.HEADING))
    fun headerSubtitleSize(context: Context): Float = textSp(context, tier(context, ShellType.CAPTION, ShellType.LABEL, ShellType.LABEL))
    fun showHeaderSubtitle(context: Context): Boolean = !isShort(context)
    fun cardTitleSize(context: Context): Float = textSp(context, tier(context, ShellType.BODY, ShellType.HEADING, ShellType.TITLE))
    fun navLabelSize(context: Context): Float = textSp(context, ShellType.CAPTION)
    fun heroMainSize(context: Context): Float = textSp(context, tier(context, ShellType.TITLE, ShellType.HEADING, ShellType.HEADING))

    /**
     * 主页左右栏权重（连接 : 应用）。接近旧版的一半对一半比例，应用格保持四列。
     */
    fun homeConnectWeight(context: Context): Float = tier(context, 5.6f, 6.4f, 5.8f)
    fun homeAppsWeight(context: Context): Float = tier(context, 4.4f, 3.6f, 4.2f)
    fun homeHeroPaneWeight(context: Context): Float = tier(context, 2.5f, 2.4f, 2.2f)
    fun homeNetworkPaneWeight(context: Context): Float = tier(context, 3.5f, 4.0f, 3.6f)

    fun settingsLeadWeight(context: Context): Float = tier(context, 1.05f, 1.1f, 1f)
    fun settingsStackWeight(context: Context): Float = tier(context, 1.25f, 1.1f, 1f)
    fun aboutLeadWeight(context: Context): Float = tier(context, 1.05f, 1.05f, 1f)
    fun aboutStackWeight(context: Context): Float = tier(context, 1.1f, 1.05f, 1f)
    /** 主页两栏之间的空隙，比设置列距更开。 */
    fun homeColumnGap(context: Context): Int = px(context, tier(context, ShellSpacing.SMALL, ShellSpacing.EXTRA_LARGE, ShellSpacing.DOUBLE_EXTRA_LARGE))

    /** 竖屏主页底部应用条高度，只留一行大图标。 */
    fun homeAppsStripH(context: Context): Int = px(context, tier(context, ShellComponentSize.APP_STRIP_COMPACT, ShellComponentSize.APP_STRIP_SHORT, ShellComponentSize.APP_STRIP_REGULAR))

    enum class DialogSize { NARROW, STANDARD, WIDE }

    fun dialogWidthPx(context: Context, size: DialogSize = DialogSize.STANDARD): Int {
        val maxDp = when (size) {
            DialogSize.NARROW -> if (isShort(context)) ShellComponentSize.DIALOG_NARROW_SHORT else ShellComponentSize.DIALOG_NARROW_REGULAR
            DialogSize.STANDARD -> if (isShort(context)) ShellComponentSize.DIALOG_STANDARD_SHORT else ShellComponentSize.DIALOG_STANDARD_REGULAR
            DialogSize.WIDE -> if (isShort(context)) ShellComponentSize.DIALOG_WIDE_SHORT else ShellComponentSize.DIALOG_WIDE_REGULAR
        }
        val dm = context.resources.displayMetrics
        val side = px(context, ShellSpacing.DOUBLE_EXTRA_LARGE)
        val screenCap = (dm.widthPixels - side * 2).coerceAtLeast(px(context, ShellComponentSize.DIALOG_SCREEN_MIN))
        return px(context, maxDp).coerceAtMost(screenCap)
    }

    fun dialogMaxHeightPx(context: Context): Int {
        val h = context.resources.displayMetrics.heightPixels
        val inset = pagePad(context) + px(context, ShellSpacing.EXTRA_SMALL)
        return (h - inset * 2).coerceAtLeast(px(context, ShellComponentSize.DIALOG_HEIGHT_MIN))
    }

    /**
     * 统一 dp→px 入口（四舍五入）。替代各文件本地重复的 dp()。
     */
    fun dp(context: Context, value: Int): Int = px(context, value)

    /** Text paint size in physical pixels, matching [textSp] for custom-drawn labels. */
    fun textPx(context: Context, value: Float): Float = if (isCockpit(context)) {
        value * ShellComponentSize.COCKPIT_PX_PER_DP
    } else {
        value * context.resources.displayMetrics.scaledDensity
    }
}

/** 便捷扩展：在 Context（Activity / View 上下文）内直接 dp(n)。 */
fun Context.dp(value: Int): Int = LayoutMetrics.dp(this, value)
