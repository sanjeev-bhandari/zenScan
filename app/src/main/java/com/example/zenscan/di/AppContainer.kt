package com.example.zenscan.di

import android.content.Context
import com.example.zenscan.data.PreferencesManager
import com.example.zenscan.detection.DetectionPipeline

class AppContainer(private val context: Context) {

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context)
    }

    /**
     * Creates a new instance of DetectionPipeline.
     * ViewModels will manage the lifecycle of the pipeline and close it when cleared.
     */
    fun createDetectionPipeline(): DetectionPipeline {
        return DetectionPipeline()
    }
}
