package com.example.zenscan.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.zenscan.detection.DetectionPipeline
import com.example.zenscan.di.AppContainer

class ViewModelFactory(
    private val application: Application,
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                MainViewModel(
                    application = application,
                    preferencesManager = container.preferencesManager
                ) as T
            }
            modelClass.isAssignableFrom(PreviewViewModel::class.java) -> {
                PreviewViewModel(
                    application = application,
                    preferencesManager = container.preferencesManager,
                    pipeline = DetectionPipeline()
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
