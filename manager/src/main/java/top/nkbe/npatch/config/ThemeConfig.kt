package top.nkbe.npatch.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "theme_settings")

enum class ThemeMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

data class ThemeSettings(
    val backgroundImageUri: String,
    val useMonet: Boolean,
    val customColor: Int,
    val themeMode: ThemeMode,
)

object ThemeConfig {
    val BG_IMAGE_URI = stringPreferencesKey("bg_image_uri")
    val USE_MONET = booleanPreferencesKey("use_monet")
    val CUSTOM_COLOR = intPreferencesKey("custom_color")
    val THEME_MODE = intPreferencesKey("theme_mode")

    fun getThemeFlow(context: Context) = context.dataStore.data.map { prefs ->
        ThemeSettings(
            backgroundImageUri = prefs[BG_IMAGE_URI] ?: "",
            useMonet = prefs[USE_MONET] ?: false,
            customColor = prefs[CUSTOM_COLOR] ?: 0xFF007AFF.toInt(),
            themeMode = ThemeMode.fromValue(prefs[THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }
}