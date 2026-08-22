package com.screenocr.monitor.utils

import android.content.Context
import android.graphics.Rect
import com.google.gson.Gson
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.model.MatchMode

class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("screen_ocr_monitor_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveConfig(config: AppConfig) {
        val regionJson = if (config.selectedRegion != null) {
            val r = config.selectedRegion!!
            "${r.left},${r.top},${r.right},${r.bottom}"
        } else ""

        prefs.edit()
            .putString("preset", config.preset)
            .putString("match_mode", config.matchMode.name)
            .putBoolean("case_sensitive", config.caseSensitive)
            .putInt("fuzzy_threshold", config.fuzzyThreshold)
            .putFloat("poll_interval", config.pollInterval.toFloat())
            .putInt("cooldown_sec", config.cooldownSec)
            .putBoolean("sound_alert", config.soundAlert)
            .putBoolean("continuous_beep", config.continuousBeep)
            .putFloat("beep_interval", config.beepInterval.toFloat())
            .putBoolean("toast_alert", config.toastAlert)
            .putBoolean("alert_once", config.alertOnce)
            .putBoolean("show_border", config.showBorder)
            .putString("selected_region", regionJson)
            .apply()
    }

    fun loadConfig(): AppConfig {
        val config = AppConfig()
        config.preset = prefs.getString("preset", "") ?: ""
        
        val modeStr = prefs.getString("match_mode", MatchMode.CONTAINS.name) ?: MatchMode.CONTAINS.name
        config.matchMode = try { MatchMode.valueOf(modeStr) } catch (e: Exception) { MatchMode.CONTAINS }
        
        config.caseSensitive = prefs.getBoolean("case_sensitive", false)
        config.fuzzyThreshold = prefs.getInt("fuzzy_threshold", 80)
        config.pollInterval = prefs.getFloat("poll_interval", 1.5f).toDouble()
        config.cooldownSec = prefs.getInt("cooldown_sec", 10)
        config.soundAlert = prefs.getBoolean("sound_alert", true)
        config.continuousBeep = prefs.getBoolean("continuous_beep", false)
        config.beepInterval = prefs.getFloat("beep_interval", 0.8f).toDouble()
        config.toastAlert = prefs.getBoolean("toast_alert", true)
        config.alertOnce = prefs.getBoolean("alert_once", true)
        config.showBorder = prefs.getBoolean("show_border", true)

        val regionStr = prefs.getString("selected_region", "") ?: ""
        if (regionStr.isNotEmpty()) {
            val parts = regionStr.split(",")
            if (parts.size == 4) {
                try {
                    config.selectedRegion = Rect(
                        parts[0].toInt(),
                        parts[1].toInt(),
                        parts[2].toInt(),
                        parts[3].toInt()
                    )
                } catch (e: Exception) {
                    config.selectedRegion = null
                }
            }
        }
        return config
    }
}
