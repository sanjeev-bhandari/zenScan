package com.example.zenscan.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zenscan.R
import com.example.zenscan.data.PreferencesManager
import com.example.zenscan.detection.DetectionPipeline
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.ExifMetadata
import com.example.zenscan.model.RegionType
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
import java.io.FileOutputStream

data class PreviewUiState(
    val bitmap: Bitmap? = null,
    val detectedRegions: List<DetectedRegion> = emptyList(),
    val isBrushMode: Boolean = false,
    val isLoading: Boolean = false,
    val summaryBadgeText: String? = null,
    val isSummaryBadgeVisible: Boolean = false,
    val isSummaryBadgeRoseColor: Boolean = true,
    val undoAvailable: Boolean = false,
    val redactAllAvailable: Boolean = false,
    val isFromHistory: Boolean = false,
    val exifMetadata: ExifMetadata? = null,
    val showExifDialog: Boolean = false
)

sealed interface PreviewUiEvent {
    data class ShowToast(val message: String) : PreviewUiEvent
    data class ShareImage(val uri: Uri) : PreviewUiEvent
    data class SaveSuccess(val path: String) : PreviewUiEvent
    object FinishActivity : PreviewUiEvent
}

class PreviewViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager,
    private val pipeline: DetectionPipeline
) : AndroidViewModel(application) {

    private val TAG = "PreviewViewModel"

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<PreviewUiEvent>()
    val event: SharedFlow<PreviewUiEvent> = _event.asSharedFlow()

    private data class RedactionState(
        val bitmap: Bitmap,
        val regions: List<DetectedRegion>,
        val isBadgeVisible: Boolean,
        val badgeText: String?,
        val redactionCount: Int
    )

    private val historyStack = mutableListOf<RedactionState>()
    private var appliedRedactionsCount = 0

    fun initImage(uri: Uri, isFromHistory: Boolean, historyFileName: String?) {
        _uiState.update { it.copy(isFromHistory = isFromHistory) }
        loadAndDetect(uri, isFromHistory, historyFileName)
    }

    private fun loadAndDetect(uri: Uri, isFromHistory: Boolean, historyFileName: String?) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val loadedBitmap = withContext(Dispatchers.IO) {
                loadBitmapFromUri(uri)
            }
            if (loadedBitmap == null) {
                _uiState.update { it.copy(isLoading = false) }
                _event.emit(PreviewUiEvent.ShowToast("Failed to load image"))
                _event.emit(PreviewUiEvent.FinishActivity)
                return@launch
            }

            val parsedExif = withContext(Dispatchers.IO) {
                loadExifFromUri(uri)
            }

            _uiState.update {
                it.copy(
                    bitmap = loadedBitmap,
                    exifMetadata = parsedExif
                )
            }

            if (isFromHistory) {
                _uiState.update { it.copy(isLoading = false) }
                showHistoryBadge(loadedBitmap, historyFileName)
            } else {
                saveStateToHistory(loadedBitmap, emptyList(), false, null)
                runDetection(loadedBitmap)
            }
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap", e)
            null
        }
    }

    private fun loadExifFromUri(uri: Uri): ExifMetadata? {
        try {
            val context = getApplication<Application>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)
                
                val make = exifInterface.getAttribute(ExifInterface.TAG_MAKE)
                val model = exifInterface.getAttribute(ExifInterface.TAG_MODEL)
                val dateTime = exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
                
                val latLong = FloatArray(2)
                val hasLatLong = exifInterface.getLatLong(latLong)
                val latitude = if (hasLatLong) latLong[0].toDouble() else null
                val longitude = if (hasLatLong) latLong[1].toDouble() else null
                
                val width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).let { if (it == 0) null else it }
                val height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).let { if (it == 0) null else it }
                
                val attributes = mutableListOf<Pair<String, String>>()
                val tagsToExtract = listOf(
                    ExifInterface.TAG_MAKE to "Camera Make",
                    ExifInterface.TAG_MODEL to "Camera Model",
                    ExifInterface.TAG_DATETIME to "Date & Time",
                    ExifInterface.TAG_IMAGE_WIDTH to "Width (px)",
                    ExifInterface.TAG_IMAGE_LENGTH to "Height (px)",
                    ExifInterface.TAG_GPS_LATITUDE to "GPS Latitude",
                    ExifInterface.TAG_GPS_LONGITUDE to "GPS Longitude",
                    ExifInterface.TAG_EXPOSURE_TIME to "Exposure Time",
                    ExifInterface.TAG_F_NUMBER to "F-Number",
                    ExifInterface.TAG_ISO_SPEED_RATINGS to "ISO Speed",
                    ExifInterface.TAG_FOCAL_LENGTH to "Focal Length",
                    ExifInterface.TAG_SOFTWARE to "Software",
                    ExifInterface.TAG_ORIENTATION to "Orientation",
                    ExifInterface.TAG_FLASH to "Flash Setting"
                )
                
                for ((tag, label) in tagsToExtract) {
                    val value = exifInterface.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        attributes.add(label to value)
                    }
                }
                
                return ExifMetadata(
                    make = make,
                    model = model,
                    dateTime = dateTime,
                    latitude = latitude,
                    longitude = longitude,
                    width = width,
                    height = height,
                    allAttributes = attributes
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EXIF metadata", e)
        }
        return null
    }

    fun showExifMetadata(visible: Boolean) {
        _uiState.update { it.copy(showExifDialog = visible) }
    }

    private fun showHistoryBadge(bitmap: Bitmap, fileName: String?) {
        var detailsText = "Redacted History File"
        if (fileName != null && fileName.startsWith("ZenScan_") && fileName.endsWith(".jpg")) {
            try {
                val payload = fileName.removePrefix("ZenScan_").removeSuffix(".jpg")
                val parts = payload.split("_")
                if (parts.size >= 2) {
                    val count = parts[1].toIntOrNull()
                    detailsText = if (count != null && count > 0) {
                        "$count sensitive item(s) protected"
                    } else {
                        "Custom brush/manual redaction"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing metadata from filename", e)
            }
        }

        _uiState.update {
            it.copy(
                summaryBadgeText = detailsText,
                isSummaryBadgeVisible = true,
                isSummaryBadgeRoseColor = false,
                redactAllAvailable = false
            )
        }

        saveStateToHistory(bitmap, emptyList(), true, detailsText)
    }

    private fun runDetection(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val regions = withContext(Dispatchers.Default) {
                    pipeline.detect(bitmap)
                }

                _uiState.update {
                    it.copy(
                        detectedRegions = regions,
                        isLoading = false
                    )
                }
                updateBadge(regions)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _event.emit(PreviewUiEvent.ShowToast("Detection failed"))
            }
        }
    }

    private fun updateBadge(regions: List<DetectedRegion>) {
        val piiCount = regions.count { it.type == RegionType.PII_TEXT }
        val faceCount = regions.count { it.type == RegionType.FACE }
        val total = piiCount + faceCount

        val text = if (total == 0) {
            "No sensitive content detected"
        } else {
            val context = getApplication<Application>()
            context.resources.getQuantityString(R.plurals.found_sensitive, total, total)
        }

        _uiState.update {
            it.copy(
                summaryBadgeText = text,
                isSummaryBadgeVisible = true,
                isSummaryBadgeRoseColor = total > 0
            )
        }

        // Overwrite initial loaded state so first undo returns to scanned region boundaries
        if (historyStack.isNotEmpty()) {
            val removed = historyStack.removeAt(historyStack.lastIndex)
            removed.bitmap.recycle()
        }

        _uiState.value.bitmap?.let {
            saveStateToHistory(it, regions, true, text)
        }
        updateRedactAllButtonState()
    }

    private fun saveStateToHistory(
        bitmap: Bitmap,
        regions: List<DetectedRegion>,
        isBadgeVisible: Boolean,
        badgeText: String?
    ) {
        try {
            val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            historyStack.add(RedactionState(bmpCopy, regions.toList(), isBadgeVisible, badgeText, appliedRedactionsCount))

            if (historyStack.size > 15) {
                val removed = historyStack.removeAt(0)
                removed.bitmap.recycle()
            }
            _uiState.update { it.copy(undoAvailable = historyStack.size > 1) }
        } catch (e: OutOfMemoryError) {
            viewModelScope.launch {
                _event.emit(PreviewUiEvent.ShowToast("Memory full, unable to save undo state"))
            }
        }
    }

    fun toggleRegionRedaction(region: DetectedRegion) {
        val updatedRegions = _uiState.value.detectedRegions.map {
            if (it.boundingBox == region.boundingBox && it.label == region.label) {
                it.copy(isRedacted = !it.isRedacted)
            } else {
                it
            }
        }

        val activePii = updatedRegions.count { it.isRedacted && it.type == RegionType.PII_TEXT }
        val activeFace = updatedRegions.count { it.isRedacted && it.type == RegionType.FACE }
        val totalActive = activePii + activeFace
        val isRose = totalActive > 0

        val context = getApplication<Application>()
        val badgeText = if (totalActive == 0) {
            "No sensitive content detected"
        } else {
            context.resources.getQuantityString(R.plurals.found_sensitive, totalActive, totalActive)
        }

        _uiState.update {
            it.copy(
                detectedRegions = updatedRegions,
                summaryBadgeText = badgeText,
                isSummaryBadgeVisible = true,
                isSummaryBadgeRoseColor = isRose
            )
        }

        _uiState.value.bitmap?.let { bmp ->
            saveStateToHistory(bmp, updatedRegions, true, badgeText)
        }
        updateRedactAllButtonState()
    }

    fun toggleBrushMode() {
        val isEnabled = !_uiState.value.isBrushMode
        _uiState.update { it.copy(isBrushMode = isEnabled) }
        updateRedactAllButtonState()
    }

    fun bakeBrushStroke(path: Path, strokeWidth: Float) {
        val bitmap = _uiState.value.bitmap ?: return
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val updatedBitmap = withContext(Dispatchers.Default) {
                    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw OutOfMemoryError("Failed to allocate bitmap copy")
                    val canvas = Canvas(result)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        this.strokeWidth = strokeWidth
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                    canvas.drawPath(path, paint)
                    result
                }

                appliedRedactionsCount++

                _uiState.update {
                    it.copy(
                        bitmap = updatedBitmap,
                        isLoading = false
                    )
                }

                saveStateToHistory(
                    updatedBitmap,
                    _uiState.value.detectedRegions,
                    _uiState.value.isSummaryBadgeVisible,
                    _uiState.value.summaryBadgeText
                )
                _event.emit(PreviewUiEvent.ShowToast("Redaction applied"))
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isLoading = false) }
                _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to apply brush stroke"))
            }
        }
    }

    fun performUndo() {
        if (historyStack.size > 1) {
            val previousState = historyStack[historyStack.lastIndex - 1]
            val restoredBitmap = try {
                previousState.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: OutOfMemoryError) {
                viewModelScope.launch {
                    _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to perform Undo"))
                }
                return
            }

            // Remove and recycle current state only after safe copy of previous state is created
            val current = historyStack.removeAt(historyStack.lastIndex)
            current.bitmap.recycle()

            appliedRedactionsCount = previousState.redactionCount

            _uiState.update {
                it.copy(
                    bitmap = restoredBitmap,
                    detectedRegions = previousState.regions,
                    summaryBadgeText = previousState.badgeText,
                    isSummaryBadgeVisible = previousState.isBadgeVisible,
                    isSummaryBadgeRoseColor = if (_uiState.value.isFromHistory) false else {
                        val activePii = previousState.regions.count { r -> r.isRedacted && r.type == RegionType.PII_TEXT }
                        val activeFace = previousState.regions.count { r -> r.isRedacted && r.type == RegionType.FACE }
                        (activePii + activeFace) > 0
                    },
                    undoAvailable = historyStack.size > 1
                )
            }

            updateRedactAllButtonState()
            viewModelScope.launch {
                _event.emit(PreviewUiEvent.ShowToast("Undo successful"))
            }
        }
    }

    fun applyRedactions() {
        val bitmap = _uiState.value.bitmap ?: return
        val activeRegions = _uiState.value.detectedRegions.filter { it.isRedacted }
        if (activeRegions.isEmpty()) {
            viewModelScope.launch {
                _event.emit(PreviewUiEvent.ShowToast("No active regions to redact"))
            }
            return
        }

        appliedRedactionsCount += activeRegions.size
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val redacted = withContext(Dispatchers.Default) {
                    redactBitmap(bitmap, activeRegions)
                } ?: throw OutOfMemoryError("Failed to allocate redacted bitmap copy")

                _uiState.update {
                    it.copy(
                        bitmap = redacted,
                        detectedRegions = emptyList(),
                        isSummaryBadgeVisible = false,
                        summaryBadgeText = null,
                        isLoading = false
                    )
                }

                saveStateToHistory(redacted, emptyList(), false, null)
                updateRedactAllButtonState()
                _event.emit(PreviewUiEvent.ShowToast("Redactions applied"))
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isLoading = false) }
                appliedRedactionsCount -= activeRegions.size
                _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to apply redactions"))
            }
        }
    }

    private fun redactBitmap(bitmap: Bitmap, regions: List<DetectedRegion>): Bitmap? {
        val result = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(result)

        val redactionStyle = preferencesManager.getRedactionStyle()
        val paint = Paint().apply {
            this.style = Paint.Style.FILL
        }

        when (redactionStyle) {
            "black" -> paint.color = Color.BLACK
            "slate" -> paint.color = "#1E293B".toColorInt()
        }

        for (region in regions) {
            val r = region.boundingBox
            val left = maxOf(0, r.left)
            val top = maxOf(0, r.top)
            val right = minOf(result.width, r.right)
            val bottom = minOf(result.height, r.bottom)

            if (right <= left || bottom <= top) continue

            if (redactionStyle == "blur") {
                try {
                    val cropped = Bitmap.createBitmap(result, left, top, right - left, bottom - top)
                    val blurred = blurBitmap(cropped, 10)
                    canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)
                    blurred.recycle()
                    cropped.recycle()
                } catch (e: Throwable) {
                    val fallbackPaint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), 12f, 12f, fallbackPaint)
                }
            } else if (redactionStyle == "pixelate") {
                try {
                    val cropped = Bitmap.createBitmap(result, left, top, right - left, bottom - top)
                    val pixelated = pixelateBitmap(cropped, 12)
                    canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), null)
                    pixelated.recycle()
                    cropped.recycle()
                } catch (e: Throwable) {
                    val fallbackPaint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), 12f, 12f, fallbackPaint)
                }
            } else {
                canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), 12f, 12f, paint)
            }
        }
        return result
    }

    private fun pixelateBitmap(src: Bitmap, pixelSize: Int): Bitmap {
        val width = src.width
        val height = src.height
        val safePixelSize = pixelSize.coerceIn(1, minOf(width, height))
        val scaledWidth = (width / safePixelSize).coerceAtLeast(1)
        val scaledHeight = (height / safePixelSize).coerceAtLeast(1)

        val tiny = Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, false)
        val result = Bitmap.createScaledBitmap(tiny, width, height, false)
        tiny.recycle()
        return result
    }

    private fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val temp = IntArray(width * height)

        // 1. Horizontal Pass
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until width) {
                        val p = pixels[y * width + nx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                temp[y * width + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }

        // 2. Vertical Pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until height) {
                        val p = temp[ny * width + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }

        val result = createBitmap(width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun exportAndShareImage(showMetadataToast: Boolean = false) {
        val bitmap = _uiState.value.bitmap
        if (bitmap == null) {
            viewModelScope.launch {
                _event.emit(PreviewUiEvent.ShowToast("No image to export"))
            }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            if (showMetadataToast) {
                _event.emit(PreviewUiEvent.ShowToast("Stripping GPS & EXIF data..."))
            }

            var exceptionMessage: String? = null
            val sharedFile = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                    sharedDir.listFiles()?.forEach { it.delete() }

                    val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")
                    val quality = preferencesManager.getExportQuality()

                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }

                    if (!_uiState.value.isFromHistory) {
                        val historyDir = File(context.filesDir, "redacted_history").apply { mkdirs() }
                        val historyFile = File(historyDir, "ZenScan_${System.currentTimeMillis()}_${appliedRedactionsCount}_items.jpg")
                        FileOutputStream(historyFile).use { outHistory ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outHistory)
                        }
                    }
                    file
                } catch (e: Exception) {
                    exceptionMessage = "Export error: " + e.toString()
                    null
                }
            }

            _uiState.update { it.copy(isLoading = false) }

            if (sharedFile != null) {
                try {
                    val context = getApplication<Application>()
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.file_provider",
                        sharedFile
                    )
                    _event.emit(PreviewUiEvent.ShareImage(contentUri))
                } catch (e: Exception) {
                    _event.emit(PreviewUiEvent.ShowToast("Failed to share image: ${e.message}"))
                }
            } else {
                _event.emit(PreviewUiEvent.ShowToast(exceptionMessage ?: "Failed to export image"))
            }
        }
    }

    fun saveToGallery() {
        val bitmap = _uiState.value.bitmap
        if (bitmap == null) {
            viewModelScope.launch {
                _event.emit(PreviewUiEvent.ShowToast("No image to save"))
            }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            var isSuccess = false
            var savedPath: String? = null

            withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val resolver = context.contentResolver
                    val quality = preferencesManager.getExportQuality()
                    val filename = "ZenScan_${System.currentTimeMillis()}.jpg"

                    val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZenScan")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    val imageUri = resolver.insert(imageCollection, contentValues)
                    if (imageUri != null) {
                        resolver.openOutputStream(imageUri).use { out ->
                            if (out != null) {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                                isSuccess = true
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(imageUri, contentValues, null, null)
                        }

                        savedPath = "Pictures/ZenScan/$filename"
                    }

                    if (!_uiState.value.isFromHistory) {
                        val historyDir = File(context.filesDir, "redacted_history").apply { mkdirs() }
                        val historyFile = File(historyDir, "ZenScan_${System.currentTimeMillis()}_${appliedRedactionsCount}_items.jpg")
                        FileOutputStream(historyFile).use { outHistory ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outHistory)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving image to gallery", e)
                }
            }

            _uiState.update { it.copy(isLoading = false) }

            if (isSuccess) {
                _event.emit(PreviewUiEvent.SaveSuccess(savedPath ?: "Successfully"))
                _event.emit(PreviewUiEvent.FinishActivity)
            } else {
                _event.emit(PreviewUiEvent.ShowToast("Failed to save image to gallery"))
            }
        }
    }

    private fun updateRedactAllButtonState() {
        val isBrush = _uiState.value.isBrushMode
        val hasActive = _uiState.value.detectedRegions.any { it.isRedacted }
        _uiState.update { it.copy(redactAllAvailable = !isBrush && hasActive) }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.close()
        historyStack.forEach { it.bitmap.recycle() }
        historyStack.clear()
    }
}
