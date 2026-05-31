package com.example.zenscan

import android.app.Application
import com.example.zenscan.di.AppContainer

class ZenScanApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
