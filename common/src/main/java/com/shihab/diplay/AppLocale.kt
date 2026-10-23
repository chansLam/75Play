// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.app.Activity
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.compat.realSdkInt
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.shihab.diplay.host.R
import java.util.Locale

/** Platform app locales on Android 13+, with a persisted context override on older Android. */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    const val TRADITIONAL_CHINESE = "zh-TW"
    const val ARABIC = "ar"
    const val RUSSIAN = "ru"
    const val SPANISH = "es"
    const val UKRAINIAN = "uk"

    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ARABIC, RUSSIAN, SPANISH, UKRAINIAN)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    fun preference(context: Context): String {
        if (realSdkInt() >= 33) return platformLocales("preference", context) as String
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in ALL } ?: SYSTEM
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        if (realSdkInt() >= 33) {
            platformLocales("save", context, language)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /** On Android 13+, the OS is the single source of truth for the app language. */
    fun wrap(context: Context): Context {
        if (realSdkInt() >= 33) {
            platformLocales("migrateIfNeeded", context)
            return context
        }
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * Before Android 13, some head units run apps in a compatibility mode that puts the system language
     * back into the activity's resources after [wrap]. Writes the chosen language again; returns true
     * if it had to. System default writes the system language back, because the process keeps the
     * resources that an earlier choice changed.
     */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        if (realSdkInt() >= 33) return false
        val system = if (realSdkInt() >= 24) topLocale(Resources.getSystem().configuration)
            else Resources.getSystem().configuration.locale
        val current = if (realSdkInt() >= 24) topLocale(context.resources.configuration)
            else context.resources.configuration.locale
        return applyLocale(context, system, current)
    }

    private fun topLocale(configuration: android.content.res.Configuration): java.util.Locale {
        val type = Class.forName("com.shihab.diplay.Api24Locales")
        return type.getMethod("top", android.content.res.Configuration::class.java)
            .invoke(null, configuration) as java.util.Locale
    }

    private fun platformLocales(method: String, vararg args: Any?): Any? {
        val type = Class.forName("com.shihab.diplay.PlatformAppLocales")
        val parameterTypes = args.map { arg ->
            when (arg) {
                is Context -> Context::class.java
                is String -> String::class.java
                // None of the reflected PlatformAppLocales methods take a nullable parameter;
                // a null here means a caller passed an unexpected argument. Fail loudly with a
                // readable message instead of an NPE off `arg!!`.
                null -> throw IllegalStateException(
                    "platformLocales($method): unexpected null argument (expected Context or String)",
                )
                else -> arg.javaClass
            }
        }.toTypedArray()
        return type.getMethod(method, *parameterTypes).invoke(null, *args)
    }

    @Suppress("DEPRECATION")
    private fun applyLocale(context: Context, systemLocale: Locale, currentLocale: Locale): Boolean {
        val locale = locale(preference(context)) ?: systemLocale
        if (currentLocale == locale) return false
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }

    /**
     * Persist [language] and refresh UI. On Android 13+ LocaleManager recreates activities;
     * on KitKat head units we must [Activity.recreate] ourselves.
     */
    fun apply(activity: Activity, language: String) {
        require(language in ALL)
        if (language == preference(activity)) return
        save(activity, language)
        if (realSdkInt() < 33) activity.recreate()
    }

    fun showPicker(activity: Activity) {
        val labels = ALL.map { displayName(activity, it) }
        val selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        DiPlayDialog.showSingleChoice(
            activity, activity.getString(R.string.language_app_language), labels, selected,
        ) { index -> apply(activity, ALL[index]) }
    }

    /** Names stay in their native form for every language; only "system default" is localized. */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> context.getString(R.string.locale_simplified_chinese)
        TRADITIONAL_CHINESE -> context.getString(R.string.locale_traditional_chinese)
        ARABIC -> "العربية"
        RUSSIAN -> "Русский"
        SPANISH -> "Español"
        UKRAINIAN -> "Українська"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
        ARABIC -> Locale("ar")
        RUSSIAN -> Locale("ru")
        SPANISH -> Locale("es")
        UKRAINIAN -> Locale("uk")
        else -> null
    }

    /** 阿语（RTL 文字）判断：以当前 resources 的 locale 为准。 */
    fun isArabic(resources: Resources): Boolean =
        resources.configuration.locale.language == ARABIC

    /** 根容器按阿语切 RTL，让代码构建的水平 LinearLayout / gravity start-end 自然镜像。 */
    fun applyRootLayoutDirection(activity: Activity) {
        if (isArabic(activity.resources)) {
            activity.window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
    }
}
