package com.shihab.diplay

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

/**
 * Android 13 per-app locales. Loaded only when [com.shihab.diplay.compat.realSdkInt] is at least 33.
 */
internal object PlatformAppLocales {
    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_MIGRATED = "app_language_platform_migrated"

    @JvmStatic
    fun preference(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return AppLocale.SYSTEM
        val selected = locales[0]
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
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
    }

    @JvmStatic
    fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        val previous = locale(prefs.getString(KEY_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM)
        if (manager.applicationLocales.isEmpty && previous != null) {
            manager.applicationLocales = LocaleList(previous)
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
}
