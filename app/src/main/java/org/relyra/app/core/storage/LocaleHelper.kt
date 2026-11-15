package org.relyra.app.core.storage

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language (Arabic / English / follow the system). Applied by wrapping the base context of the Application and the
 * Activity, so every string, the layout direction (RTL for Arabic, LTR for English) and Compose all follow the choice.
 * The Activity is recreated after a change; no reinstall or restart is needed.
 */
object LocaleHelper {
    const val PREFS = "relyra_settings"
    const val KEY = "app_language"
    val SUPPORTED = listOf("", "en", "ar")

    fun saved(base: Context): String =
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")?.takeIf { it in SUPPORTED } ?: ""

    fun wrap(base: Context): Context {
        val tag = saved(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
