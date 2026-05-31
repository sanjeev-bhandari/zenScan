package com.example.zenscan.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = try {
        EncryptedSharedPreferences.create(
            context,
            "zenscan_encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback in case of keystore issues (extremely rare, but good safety measure)
        context.getSharedPreferences("zenscan_prefs", Context.MODE_PRIVATE)
    }

    fun getThemeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun getRedactionStyle(): String = prefs.getString("redaction_style", "black") ?: "black"

    fun setRedactionStyle(style: String) {
        prefs.edit().putString("redaction_style", style).apply()
    }

    fun getExportQuality(): Int = prefs.getInt("export_quality", 95)

    fun setExportQuality(quality: Int) {
        prefs.edit().putInt("export_quality", quality).apply()
    }

    // Encrypted History Metadata Registry for persistent redaction items count
    fun getHistoryMetadata(fileName: String): String? {
        return prefs.getString("history_meta_$fileName", null)
    }

    fun saveHistoryMetadata(fileName: String, metadata: String) {
        prefs.edit().putString("history_meta_$fileName", metadata).apply()
    }

    fun removeHistoryMetadata(fileName: String) {
        prefs.edit().remove("history_meta_$fileName").apply()
    }
}
