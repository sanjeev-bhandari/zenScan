package com.example.zenscan.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.zenscan.model.CustomFilterRule
import org.json.JSONArray
import org.json.JSONObject

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

    // Custom Redaction Rules Storage
    fun getCustomRules(): List<CustomFilterRule> {
        val jsonStr = prefs.getString("custom_redaction_rules", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<CustomFilterRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CustomFilterRule(
                        id = obj.getString("id"),
                        pattern = obj.getString("pattern"),
                        isRegex = obj.getBoolean("isRegex"),
                        label = obj.getString("label")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomRules(rules: List<CustomFilterRule>) {
        val array = JSONArray()
        for (rule in rules) {
            val obj = JSONObject().apply {
                put("id", rule.id)
                put("pattern", rule.pattern)
                put("isRegex", rule.isRegex)
                put("label", rule.label)
            }
            array.put(obj)
        }
        prefs.edit().putString("custom_redaction_rules", array.toString()).apply()
    }
}
