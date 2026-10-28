package com.example.zenscan.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.toColorInt
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zenscan.R
import com.example.zenscan.data.PreferencesManager
import com.example.zenscan.detection.DetectionPipeline
import com.example.zenscan.model.DetectedRegion
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
    val exifMetadata: List<Pair<String, String>>? = null,
    val showExifDialog: Boolean = false
)

sealed interface PreviewUiEvent {
    data class ShowToast(val message: String) : PreviewUiEvent
    data class ShareImage(val uri: Uri) : PreviewUiEvent
    data class SaveSuccess(val path: String) : PreviewUiEvent
    object FinishActivity : PreviewUiEvent
}

sealed interface RedactionCommand {
    data class DrawBrushStroke(val path: Path, val strokeWidth: Float) : RedactionCommand
    data class ApplyRegionRedactions(val regions: List<DetectedRegion>, val style: String) : RedactionCommand
}

data class RedactionHistoryState(
    val commands: List<RedactionCommand>,
    val detectedRegions: List<DetectedRegion>,
    val isBadgeVisible: Boolean,
    val badgeText: String?,
    val isSummaryRoseColor: Boolean,
    val redactionCount: Int
)

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

    private var cleanBitmap: Bitmap? = null
    private val historyStack = mutableListOf<RedactionHistoryState>()
    private var appliedRedactionsCount = 0
    private var isSavedToHistoryInSession = false

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

            // Keep a clean copy of the original bitmap to apply commands on-the-fly
            cleanBitmap = loadedBitmap.copy(Bitmap.Config.ARGB_8888, false)

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
                saveStateToHistory(emptyList(), emptyList(), false, null, true)
                runDetection(loadedBitmap)
            }
        }
    }

    fun rescanImage() {
        val bmp = cleanBitmap ?: return
        _uiState.update { it.copy(isLoading = true) }
        runDetection(bmp)
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()

            // 1. Read EXIF orientation before stripping
            var orientation = ExifInterface.ORIENTATION_NORMAL
            context.contentResolver.openInputStream(uri)?.use { stream ->
                try {
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read EXIF orientation", e)
                }
            }

            // 2. Sample size downscaling to prevent OOM on massive (e.g. 108MP) images
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            val maxDimension = 3072 // Safe max editing dimension
            val maxSide = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            val inSampleSize = if (maxSide > maxDimension) {
                Integer.highestOneBit(maxSide / maxDimension).coerceAtLeast(1)
            } else {
                1
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            // 3. Rotate bitmap if required by EXIF orientation tag
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> { /* No rotation needed */ }
            }

            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) {
                    rawBitmap.recycle()
                }
                rotated
            } else {
                rawBitmap
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading bitmap", e)
            null
        }
    }

    private fun loadExifFromUri(uri: Uri): List<Pair<String, String>>? {
        try {
            val context = getApplication<Application>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)
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
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "ISO Speed",
                    ExifInterface.TAG_FOCAL_LENGTH to "Focal Length",
                    ExifInterface.TAG_SOFTWARE to "Software",
                    ExifInterface.TAG_ORIENTATION to "Orientation",
                    ExifInterface.TAG_FLASH to "Flash Setting"
                )
                return tagsToExtract.mapNotNull { (tag, label) ->
                    exifInterface.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { label to it }
                }
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
        var detailsText = preferencesManager.getHistoryMetadata(fileName ?: "")
        if (detailsText == null) {
            detailsText = "Redacted History File"
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
        }

        _uiState.update {
            it.copy(
                summaryBadgeText = detailsText,
                isSummaryBadgeVisible = true,
                isSummaryBadgeRoseColor = false,
                redactAllAvailable = false
            )
        }

        saveStateToHistory(emptyList(), emptyList(), true, detailsText, false)
    }

    private fun runDetection(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val customRules = preferencesManager.getCustomRules()
                val regions = withContext(Dispatchers.Default) {
                    pipeline.detect(bitmap, customRules)
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
            historyStack.removeAt(historyStack.lastIndex)
        }

        saveStateToHistory(emptyList(), regions, true, text, total > 0)
        updateRedactAllButtonState()
    }

    private fun saveStateToHistory(
        commands: List<RedactionCommand>,
        regions: List<DetectedRegion>,
        isBadgeVisible: Boolean,
        badgeText: String?,
        isRoseColor: Boolean
    ) {
        historyStack.add(
            RedactionHistoryState(
                commands = commands.toList(),
                detectedRegions = regions.toList(),
                isBadgeVisible = isBadgeVisible,
                badgeText = badgeText,
                isSummaryRoseColor = isRoseColor,
                redactionCount = appliedRedactionsCount
            )
        )

        // Strict limit of 15 states to protect RAM
        while (historyStack.size > 15) {
            historyStack.removeAt(0)
        }
        _uiState.update { it.copy(undoAvailable = historyStack.size > 1) }
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

        val lastState = historyStack.lastOrNull()
        val currentCommands = lastState?.commands ?: emptyList()

        saveStateToHistory(currentCommands, updatedRegions, true, badgeText, isRose)
        updateRedactAllButtonState()
    }

    fun toggleBrushMode() {
        val isEnabled = !_uiState.value.isBrushMode
        _uiState.update { it.copy(isBrushMode = isEnabled) }
        updateRedactAllButtonState()
    }

    fun bakeBrushStroke(path: Path, strokeWidth: Float) {
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val lastState = historyStack.lastOrNull()
                val currentCommands = lastState?.commands ?: emptyList()
                val newCommands = currentCommands + RedactionCommand.DrawBrushStroke(Path(path), strokeWidth)

                appliedRedactionsCount++

                val nextState = RedactionHistoryState(
                    commands = newCommands,
                    detectedRegions = _uiState.value.detectedRegions,
                    isBadgeVisible = _uiState.value.isSummaryBadgeVisible,
                    badgeText = _uiState.value.summaryBadgeText,
                    isSummaryRoseColor = _uiState.value.isSummaryBadgeRoseColor,
                    redactionCount = appliedRedactionsCount
                )

                val updatedBitmap = withContext(Dispatchers.Default) {
                    renderBitmapForState(nextState) ?: throw OutOfMemoryError("Failed to render bitmap")
                }

                historyStack.add(nextState)
                _uiState.update { it.copy(undoAvailable = historyStack.size > 1) }

                _uiState.update {
                    it.copy(
                        bitmap = updatedBitmap,
                        isLoading = false
                    )
                }

                _event.emit(PreviewUiEvent.ShowToast("Redaction applied"))
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isLoading = false) }
                _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to apply brush stroke"))
            }
        }
    }

    fun performUndo() {
        if (historyStack.size > 1) {
            historyStack.removeAt(historyStack.lastIndex)
            val previousState = historyStack.last()

            val restoredBitmap = try {
                renderBitmapForState(previousState)
            } catch (e: OutOfMemoryError) {
                null
            }

            if (restoredBitmap == null) {
                viewModelScope.launch {
                    _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to perform Undo"))
                }
                return
            }

            appliedRedactionsCount = previousState.redactionCount

            _uiState.update {
                it.copy(
                    bitmap = restoredBitmap,
                    detectedRegions = previousState.detectedRegions,
                    summaryBadgeText = previousState.badgeText,
                    isSummaryBadgeVisible = previousState.isBadgeVisible,
                    isSummaryBadgeRoseColor = previousState.isSummaryRoseColor,
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
                val lastState = historyStack.lastOrNull()
                val currentCommands = lastState?.commands ?: emptyList()
                val style = preferencesManager.getRedactionStyle()
                val newCommands = currentCommands + RedactionCommand.ApplyRegionRedactions(activeRegions, style)

                val nextState = RedactionHistoryState(
                    commands = newCommands,
                    detectedRegions = emptyList(),
                    isBadgeVisible = false,
                    badgeText = null,
                    isSummaryRoseColor = false,
                    redactionCount = appliedRedactionsCount
                )

                val redacted = withContext(Dispatchers.Default) {
                    renderBitmapForState(nextState) ?: throw OutOfMemoryError("Failed to render redacted bitmap")
                }

                historyStack.add(nextState)
                _uiState.update { it.copy(undoAvailable = historyStack.size > 1) }

                _uiState.update {
                    it.copy(
                        bitmap = redacted,
                        detectedRegions = emptyList(),
                        isSummaryBadgeVisible = false,
                        summaryBadgeText = null,
                        isLoading = false
                    )
                }

                updateRedactAllButtonState()
                _event.emit(PreviewUiEvent.ShowToast("Redactions applied"))
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isLoading = false) }
                appliedRedactionsCount -= activeRegions.size
                _event.emit(PreviewUiEvent.ShowToast("Low memory: Unable to apply redactions"))
            }
        }
    }

    private fun renderBitmapForState(state: RedactionHistoryState): Bitmap? {
        val base = cleanBitmap ?: return null
        val result = try {
            base.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            return null
        }
        for (command in state.commands) {
            applyCommandToBitmap(result, command)
        }
        return result
    }

    private fun applyCommandToBitmap(bitmap: Bitmap, command: RedactionCommand) {
        val canvas = Canvas(bitmap)
        when (command) {
            is RedactionCommand.DrawBrushStroke -> {
                val paint = Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = command.strokeWidth
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                canvas.drawPath(command.path, paint)
            }
            is RedactionCommand.ApplyRegionRedactions -> {
                redactBitmapInPlace(bitmap, command.regions, command.style)
            }
        }
    }

    private fun redactBitmapInPlace(bitmap: Bitmap, regions: List<DetectedRegion>, redactionStyle: String) {
        val canvas = Canvas(bitmap)
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
            val right = minOf(bitmap.width, r.right)
            val bottom = minOf(bitmap.height, r.bottom)

            if (right <= left || bottom <= top) continue

            if (redactionStyle == "blur") {
                try {
                    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    val blurred = blurBitmap(cropped, 16)
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
                    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    val pixelated = pixelateBitmap(cropped, 16)
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
    }

    private fun pixelateBitmap(src: Bitmap, pixelSize: Int): Bitmap {
        val width = src.width
        val height = src.height
        // Enforce safe minimum pixel block size so underlying characters cannot be reconstructed
        val safePixelSize = pixelSize.coerceAtLeast(16).coerceIn(1, minOf(width, height))
        val scaledWidth = (width / safePixelSize).coerceAtLeast(1)
        val scaledHeight = (height / safePixelSize).coerceAtLeast(1)

        val tiny = Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, false)
        val result = Bitmap.createScaledBitmap(tiny, width, height, false)
        tiny.recycle()
        return result
    }

    private fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
        // Enforce aggressive downscaling with bilinear interpolation to obliterate fine text features
        val scale = (1f / radius.coerceAtLeast(12)).coerceAtLeast(0.04f)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, w, h, true)
        val result = Bitmap.createScaledBitmap(tiny, src.width, src.height, true)
        tiny.recycle()
        return result
    }

    private fun getFlattenedBitmap(): Bitmap? {
        val currentBitmap = _uiState.value.bitmap ?: return null
        val result = try {
            currentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            return null
        }

        val activeRegions = _uiState.value.detectedRegions.filter { it.isRedacted }
        if (activeRegions.isNotEmpty()) {
            val style = preferencesManager.getRedactionStyle()
            redactBitmapInPlace(result, activeRegions, style)
        }

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
            var tempFlattenedBitmap: Bitmap? = null
            val sharedFile = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                    sharedDir.listFiles()?.forEach { it.delete() }

                    val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")
                    val quality = preferencesManager.getExportQuality()

                    tempFlattenedBitmap = getFlattenedBitmap()
                    val bitmapToSave = tempFlattenedBitmap ?: bitmap

                    FileOutputStream(file).use { out ->
                        bitmapToSave.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }

                    saveToRedactedHistory(context, bitmapToSave, quality)
                    file
                } catch (e: Exception) {
                    exceptionMessage = "Export error: " + e.toString()
                    null
                } finally {
                    tempFlattenedBitmap?.recycle()
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

            var tempFlattenedBitmap: Bitmap? = null
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
                        tempFlattenedBitmap = getFlattenedBitmap()
                        val bitmapToSave = tempFlattenedBitmap ?: bitmap

                        resolver.openOutputStream(imageUri).use { out ->
                            if (out != null) {
                                bitmapToSave.compress(Bitmap.CompressFormat.JPEG, quality, out)
                                isSuccess = true
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(imageUri, contentValues, null, null)
                        }

                        savedPath = "Pictures/ZenScan/$filename"

                        saveToRedactedHistory(context, bitmapToSave, quality)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving image to gallery", e)
                } finally {
                    tempFlattenedBitmap?.recycle()
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

    private fun saveToRedactedHistory(context: Context, bitmap: Bitmap, quality: Int) {
        if (_uiState.value.isFromHistory || isSavedToHistoryInSession) return
        val historyDir = File(context.filesDir, "redacted_history").apply { mkdirs() }
        val activeCount = _uiState.value.detectedRegions.count { it.isRedacted }
        val totalRedactedCount = appliedRedactionsCount + activeCount
        val historyFileName = "ZenScan_${System.currentTimeMillis()}_${totalRedactedCount}_items.jpg"
        val historyFile = File(historyDir, historyFileName)
        FileOutputStream(historyFile).use { outHistory ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outHistory)
        }

        val detailsText = if (totalRedactedCount > 0) {
            "$totalRedactedCount sensitive item(s) protected"
        } else {
            "Custom brush/manual redaction"
        }
        preferencesManager.saveHistoryMetadata(historyFileName, detailsText)
        isSavedToHistoryInSession = true
    }

    private fun updateRedactAllButtonState() {
        val isBrush = _uiState.value.isBrushMode
        val hasActive = _uiState.value.detectedRegions.any { it.isRedacted }
        _uiState.update { it.copy(redactAllAvailable = !isBrush && hasActive) }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.close()
        cleanBitmap?.recycle()
        cleanBitmap = null
        historyStack.clear()
    }
}
