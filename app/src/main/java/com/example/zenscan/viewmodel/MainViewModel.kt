package com.example.zenscan.viewmodel

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zenscan.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MainUiState(
    val recentFiles: List<File> = emptyList(),
    val allFiles: List<File> = emptyList(),
    val themeMode: String = "system",
    val redactionStyle: String = "black",
    val exportQuality: Int = 95,
    val isHistoryEmpty: Boolean = true
)

sealed interface MainUiEvent {
    data class ShowToast(val message: String) : MainUiEvent
    data class OpenHistoryFile(val file: File, val uri: Uri) : MainUiEvent
    data class LaunchCamera(val uri: Uri) : MainUiEvent
}

class MainViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<MainUiEvent>()
    val event: SharedFlow<MainUiEvent> = _event.asSharedFlow()

    init {
        // Load initial preferences
        _uiState.update {
            it.copy(
                themeMode = preferencesManager.getThemeMode(),
                redactionStyle = preferencesManager.getRedactionStyle(),
                exportQuality = preferencesManager.getExportQuality()
            )
        }
        loadHistoryData()
    }

    fun loadHistoryData() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val historyDir = File(context.filesDir, "redacted_history")
            val files = if (historyDir.exists()) {
                historyDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("ZenScan_") && it.name.endsWith(".jpg") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        recentFiles = files.take(3),
                        allFiles = files,
                        isHistoryEmpty = files.isEmpty()
                    )
                }
            }
        }
    }

    fun updateThemeMode(mode: String) {
        preferencesManager.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun updateRedactionStyle(style: String) {
        preferencesManager.setRedactionStyle(style)
        _uiState.update { it.copy(redactionStyle = style) }
    }

    fun updateExportQuality(quality: Int) {
        preferencesManager.setExportQuality(quality)
        _uiState.update { it.copy(exportQuality = quality) }
    }

    fun onFileClicked(file: File) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.file_provider",
                    file
                )
                _event.emit(MainUiEvent.OpenHistoryFile(file, contentUri))
            } catch (e: Exception) {
                _event.emit(MainUiEvent.ShowToast("Failed to open image"))
            }
        }
    }

    fun deleteFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = file.delete()
            withContext(Dispatchers.Main) {
                if (success) {
                    _event.emit(MainUiEvent.ShowToast("Item deleted"))
                    loadHistoryData()
                } else {
                    _event.emit(MainUiEvent.ShowToast("Failed to delete item"))
                }
            }
        }
    }

    fun wipeAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val historyDir = File(context.filesDir, "redacted_history")
            if (historyDir.exists()) {
                historyDir.listFiles()?.forEach { it.delete() }
            }
            val cacheDirShared = File(context.cacheDir, "shared_images")
            if (cacheDirShared.exists()) {
                cacheDirShared.listFiles()?.forEach { it.delete() }
            }

            withContext(Dispatchers.Main) {
                _event.emit(MainUiEvent.ShowToast("Redacted history cleared successfully"))
                loadHistoryData()
            }
        }
    }

    fun prepareCameraCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                val tempFile = File(sharedDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.file_provider",
                    tempFile
                )
                withContext(Dispatchers.Main) {
                    _event.emit(MainUiEvent.LaunchCamera(contentUri))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _event.emit(MainUiEvent.ShowToast("Failed to initialize camera storage: ${e.message}"))
                }
            }
        }
    }
}
