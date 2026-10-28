package com.example.zenscan

import android.app.Application
import com.example.zenscan.di.AppContainer

import androidx.appcompat.app.AppCompatDelegate

class ZenScanApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val targetMode = when (container.preferencesManager.getThemeMode()) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }
}
