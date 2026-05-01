package org.lsposed.npatch.config // 請確認這裡的 package 路徑與你的實際位置相符

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

// 初始化 DataStore，名稱為 "theme_settings"
val Context.dataStore by preferencesDataStore(name = "theme_settings")

object ThemeConfig {
    // 定義儲存鍵值
    val BG_IMAGE_URI = stringPreferencesKey("bg_image_uri")
    val USE_MONET = booleanPreferencesKey("use_monet")
    val CUSTOM_COLOR = intPreferencesKey("custom_color")

    // 提供一個 Flow，將取出的資料打包為 Triple
    // 預設值：無背景圖、關閉 Monet、預設顏色為 Miuix 藍 (0xFF007AFF)
    fun getThemeFlow(context: Context) = context.dataStore.data.map { prefs ->
        Triple(
            prefs[BG_IMAGE_URI] ?: "",
            prefs[USE_MONET] ?: false,
            prefs[CUSTOM_COLOR] ?: 0xFF007AFF.toInt()
        )
    }
}