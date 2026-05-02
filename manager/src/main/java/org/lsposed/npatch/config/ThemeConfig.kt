package org.lsposed.npatch.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "theme_settings")

object ThemeConfig {
    val BG_IMAGE_URI = stringPreferencesKey("bg_image_uri")
    val USE_MONET = booleanPreferencesKey("use_monet")
    val CUSTOM_COLOR = intPreferencesKey("custom_color")

    fun getThemeFlow(context: Context) = context.dataStore.data.map { prefs ->
        Triple(
            prefs[BG_IMAGE_URI] ?: "",
            prefs[USE_MONET] ?: false,
            prefs[CUSTOM_COLOR] ?: 0xFF007AFF.toInt()
        )
    }
}