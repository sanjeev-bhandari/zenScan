package com.example.zenscan.di

import android.content.Context
import com.example.zenscan.data.PreferencesManager

class AppContainer(private val context: Context) {

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context)
    }
}
