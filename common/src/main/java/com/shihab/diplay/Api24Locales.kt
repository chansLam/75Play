package com.shihab.diplay

import android.content.res.Configuration
import java.util.Locale

/** API 24 [Configuration.getLocales]. Loaded only when the real SDK is at least 24. */
internal object Api24Locales {
    @JvmStatic
    fun top(configuration: Configuration): Locale = configuration.locales[0]
}
