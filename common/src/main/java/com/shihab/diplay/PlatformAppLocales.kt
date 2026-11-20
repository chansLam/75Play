package com.shihab.diplay

import android.content.Context
import java.util.Locale

/**
 * Android 13 per-app locales. Loaded only when [com.shihab.diplay.compat.realSdkInt] is at least 33.
 */
internal object PlatformAppLocales {
    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_MIGRATED = "app_language_platform_migrated"
    private const val LOCALE_MANAGER_SERVICE = "locale"

    @JvmStatic
    fun preference(context: Context): String {
        val locales = applicationLocales(context) ?: return AppLocale.SYSTEM
        val selected = firstLocale(locales) ?: return AppLocale.SYSTEM
        if (selected.language != "zh") return selected.language
        return when (selected.script) {
            "Hant" -> AppLocale.TRADITIONAL_CHINESE
            "Hans" -> AppLocale.SIMPLIFIED_CHINESE
            else -> if (selected.country in setOf("TW", "HK", "MO")) {
                AppLocale.TRADITIONAL_CHINESE
            } else {
                AppLocale.SIMPLIFIED_CHINESE
            }
        }
    }

    @JvmStatic
    fun save(context: Context, language: String) {
        val locale = locale(language)
        setApplicationLocales(context, locale)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
    }

    @JvmStatic
    fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val previous = locale(prefs.getString(KEY_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM)
        if (applicationLocales(context)?.let(::firstLocale) == null && previous != null) {
            setApplicationLocales(context, previous)
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
    }

    private fun locale(language: String): Locale? = when (language) {
        AppLocale.ENGLISH -> Locale.ENGLISH
        AppLocale.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLocale.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
        AppLocale.ARABIC -> Locale("ar")
        AppLocale.RUSSIAN -> Locale("ru")
        AppLocale.SPANISH -> Locale("es")
        AppLocale.UKRAINIAN -> Locale("uk")
        else -> null
    }

    private fun applicationLocales(context: Context): Any? {
        val manager = context.getSystemService(LOCALE_MANAGER_SERVICE) ?: return null
        return manager.javaClass.getMethod("getApplicationLocales").invoke(manager)
    }

    private fun firstLocale(locales: Any): Locale? {
        val type = locales.javaClass
        val empty = type.getMethod("isEmpty").invoke(locales) as Boolean
        if (empty) return null
        return type.getMethod("get", Int::class.javaPrimitiveType).invoke(locales, 0) as? Locale
    }

    private fun setApplicationLocales(context: Context, locale: Locale?) {
        val manager = context.getSystemService(LOCALE_MANAGER_SERVICE) ?: return
        val localeListClass = Class.forName("android.os.LocaleList")
        val locales = if (locale == null) {
            localeListClass.getMethod("getEmptyLocaleList").invoke(null)
        } else {
            localeListClass.getConstructor(Array<Locale>::class.java).newInstance(arrayOf(locale))
        }
        manager.javaClass.getMethod("setApplicationLocales", localeListClass).invoke(manager, locales)
    }
}
