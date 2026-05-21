package com.example.zenscan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.example.zenscan.databinding.ActivityPreviewBinding
import com.example.zenscan.detection.DetectionPipeline
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val pipeline = DetectionPipeline()
    private var originalBitmap: Bitmap? = null
    private var detectedRegions: List<DetectedRegion> = emptyList()

    // Counter tracks total edits made in the current session
    private var appliedRedactionsCount = 0

    // State definition for RAM-safe Undo History incorporating applied redaction counts
    private data class RedactionState(
        val bitmap: Bitmap,
        val regions: List<DetectedRegion>,
        val isBadgeVisible: Boolean,
        val badgeText: String?,
        val redactionCount: Int
    )

    private val historyStack = mutableListOf<RedactionState>()
    private var isFromHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFromHistory = intent.getBooleanExtra("is_from_history", false)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        
        if (isFromHistory) {
            supportActionBar?.title = "Redacted History"
        } else {
            supportActionBar?.title = "Smart Scan"
        }
        
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Handle WindowInsets to prevent top Status Bar overlap on toolbar and badge dynamically
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val statusInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusInsets.top, 0, 0)

            // Position the floating summaryBadge cleanly below the toolbar (56dp height) to avoid title/menu overlaps
            binding.summaryBadge.updateLayoutParams<MarginLayoutParams> {
                val density = resources.displayMetrics.density
                val toolbarHeight = 56 * density
                topMargin = statusInsets.top + toolbarHeight.toInt() + (8 * density).toInt()
            }
            windowInsets
        }

        setupToolbarButtons()
        setupOverlayCallbacks()

        val uri = intent.data
        if (uri != null) {
            loadImageAndDetect(uri)
        } else {
            Toast.makeText(this, R.string.no_image_provided, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_preview, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_placeholder -> {
                Toast.makeText(this, "ZenScan Safe Share Active", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbarButtons() {
        binding.btnUndo.setOnClickListener {
            performUndo()
        }
        binding.btnRedactAll.setOnClickListener {
            applyRedactions()
        }
        binding.btnBrush.setOnClickListener {
            toggleBrushMode()
        }
        binding.btnExport.setOnClickListener {
            exportAndShareImage()
        }

        // Initialize button states
        updateUndoButtonState()
        updateRedactAllButtonState()

        // Handle WindowInsets to prevent bottom gesture/navigation overlap dynamically
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomControlsCard) { view, windowInsets ->
            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updateLayoutParams<MarginLayoutParams> {
                val baseMargin = (16 * resources.displayMetrics.density).toInt()
                bottomMargin = navInsets.bottom + baseMargin
                leftMargin = navInsets.left + baseMargin
                rightMargin = navInsets.right + baseMargin
            }
            windowInsets
        }
    }

    private fun setupOverlayCallbacks() {
        binding.overlayView.onRegionToggled = { region ->
            toggleRegionRedaction(region)
        }
        binding.overlayView.onBrushStrokeCompleted = { path, strokeWidth ->
            bakeBrushStroke(path, strokeWidth)
        }
    }

    private fun toggleRegionRedaction(region: DetectedRegion) {
        detectedRegions = detectedRegions.map {
            if (it.boundingBox == region.boundingBox && it.label == region.label) {
                it.copy(isRedacted = !it.isRedacted)
            } else {
                it
            }
        }
        originalBitmap?.let { bmp ->
            binding.overlayView.setRegions(detectedRegions, bmp.width, bmp.height)
        }

        val activePii = detectedRegions.count { it.isRedacted && it.type == RegionType.PII_TEXT }
        val activeFace = detectedRegions.count { it.isRedacted && it.type == RegionType.FACE }
        val totalActive = activePii + activeFace
        val badgeText = if (totalActive == 0) {
            getString(R.string.no_sensitive_found)
        } else {
            resources.getQuantityString(R.plurals.found_sensitive, totalActive, totalActive)
        }
        binding.summaryBadge.text = badgeText

        originalBitmap?.let { bmp ->
            saveStateToHistory(bmp, detectedRegions, true, badgeText)
        }
        updateRedactAllButtonState()
    }

    private fun toggleBrushMode() {
        val isEnabled = !binding.overlayView.isBrushMode
        binding.overlayView.isBrushMode = isEnabled

        if (isEnabled) {
            binding.btnBrush.setBackgroundTintList(android.content.res.ColorStateList.valueOf("#1E3A8A".toColorInt()))
            binding.btnBrush.setColorFilter(android.graphics.Color.WHITE)

            Toast.makeText(this, "Brush Mode Enabled: Draw directly to redact", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnBrush.setBackgroundTintList(android.content.res.ColorStateList.valueOf("#2E3B4E".toColorInt()))
            binding.btnBrush.setColorFilter(resources.getColor(R.color.text_dark_primary, theme))

            Toast.makeText(this, "Brush Mode Disabled", Toast.LENGTH_SHORT).show()
        }
        updateRedactAllButtonState()
    }

    private fun bakeBrushStroke(path: android.graphics.Path, strokeWidth: Float) {
        val bitmap = originalBitmap ?: return

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val updatedBitmap = withContext(Dispatchers.Default) {
                val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(result)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    isAntiAlias = true
                }
                canvas.drawPath(path, paint)
                result
            }

            appliedRedactionsCount++ // Increment session counter

            originalBitmap = updatedBitmap
            binding.imagePreview.setImageBitmap(updatedBitmap)
            binding.loadingIndicator.visibility = View.GONE

            saveStateToHistory(updatedBitmap, detectedRegions, binding.summaryBadge.isVisible, binding.summaryBadge.text?.toString())
            Toast.makeText(this@PreviewActivity, "Redaction applied", Toast.LENGTH_SHORT).show()
        }
    }

    // Capture and save deep copies of state in the history stack
    private fun saveStateToHistory(
        bitmap: Bitmap,
        regions: List<DetectedRegion>,
        isBadgeVisible: Boolean,
        badgeText: String?
    ) {
        try {
            val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            historyStack.add(RedactionState(bmpCopy, regions.toList(), isBadgeVisible, badgeText, appliedRedactionsCount))

            // Limit memory usage by restricting undo depth
            if (historyStack.size > 15) {
                val removed = historyStack.removeAt(0)
                removed.bitmap.recycle()
            }
            updateUndoButtonState()
        } catch (e: OutOfMemoryError) {
            Toast.makeText(this, "Memory full, unable to save undo state", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUndoButtonState() {
        if (historyStack.size > 1) {
            binding.btnUndo.isEnabled = true
            binding.btnUndo.alpha = 1.0f
        } else {
            binding.btnUndo.isEnabled = false
            binding.btnUndo.alpha = 0.5f
        }
    }

    private fun updateRedactAllButtonState() {
        val isBrushMode = binding.overlayView.isBrushMode
        val hasActiveRedactions = detectedRegions.any { it.isRedacted }
        val shouldEnable = !isBrushMode && hasActiveRedactions

        binding.btnRedactAll.isEnabled = shouldEnable
        binding.btnRedactAll.alpha = if (shouldEnable) 1.0f else 0.4f
    }

    private fun performUndo() {
        if (historyStack.size > 1) {
            // Remove current active state
            val current = historyStack.removeAt(historyStack.lastIndex)
            current.bitmap.recycle()

            // Retrieve previous state
            val previousState = historyStack.last()
            val restoredBitmap = previousState.bitmap.copy(Bitmap.Config.ARGB_8888, true)

            originalBitmap = restoredBitmap
            binding.imagePreview.setImageBitmap(restoredBitmap)

            // Restore detection regions and overlays
            detectedRegions = previousState.regions
            if (previousState.regions.isNotEmpty()) {
                binding.overlayView.setRegions(
                    regions = previousState.regions,
                    bmpWidth = restoredBitmap.width,
                    bmpHeight = restoredBitmap.height
                )
            } else {
                binding.overlayView.clear()
            }

            // Restore summary badge visibility and contents
            if (previousState.isBadgeVisible) {
                binding.summaryBadge.text = previousState.badgeText
                binding.summaryBadge.visibility = View.VISIBLE
                if (isFromHistory) {
                    binding.summaryBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_green, theme))
                } else {
                    binding.summaryBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_rose, theme))
                }
            } else {
                binding.summaryBadge.visibility = View.GONE
            }

            // Restore session count directly from history index
            appliedRedactionsCount = previousState.redactionCount

            updateUndoButtonState()
            updateRedactAllButtonState()
            Toast.makeText(this, "Undo successful", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageAndDetect(uri: Uri) {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmapFromUri(uri)
            }
            if (bitmap == null) {
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PreviewActivity, R.string.failed_to_load_image, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            originalBitmap = bitmap
            binding.imagePreview.setImageBitmap(bitmap)

            if (isFromHistory) {
                binding.loadingIndicator.visibility = View.GONE
                showHistoryBadge()
            } else {
                // Save initial loaded image state
                saveStateToHistory(bitmap, emptyList(), false, null)
                runDetection(bitmap)
            }
        }
    }

    private fun showHistoryBadge() {
        val fileName = intent.getStringExtra("history_file_name")
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
            } catch (e: java.lang.Exception) {
                // Keep default detailsText
            }
        }
        
        binding.summaryBadge.text = detailsText
        binding.summaryBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_green, theme))
        binding.summaryBadge.visibility = View.VISIBLE
        
        // Disable 'Redact All' since it's already redacted
        binding.btnRedactAll.isEnabled = false
        binding.btnRedactAll.alpha = 0.4f
        
        // Save the loaded history state into the undo history stack with the badge set to true
        originalBitmap?.let { bmp ->
            saveStateToHistory(bmp, emptyList(), true, detailsText)
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runDetection(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                val regions = withContext(Dispatchers.Default) {
                    pipeline.detect(bitmap)
                }
                detectedRegions = regions

                binding.overlayView.setRegions(
                    regions = regions,
                    bmpWidth = bitmap.width,
                    bmpHeight = bitmap.height
                )

                updateBadge(regions)
            } catch (e: Exception) {
                Toast.makeText(this@PreviewActivity, R.string.detection_failed, Toast.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun updateBadge(regions: List<DetectedRegion>) {
        val piiCount = regions.count { it.type == RegionType.PII_TEXT }
        val faceCount = regions.count { it.type == RegionType.FACE }
        val total = piiCount + faceCount

        val text = if (total == 0) {
            getString(R.string.no_sensitive_found)
        } else {
            resources.getQuantityString(R.plurals.found_sensitive, total, total)
        }
        binding.summaryBadge.text = text
        binding.summaryBadge.visibility = View.VISIBLE

        // Overwrite initial loaded state so first undo returns to scanned region boundaries
        if (historyStack.isNotEmpty()) {
            val removed = historyStack.removeAt(historyStack.lastIndex)
            removed.bitmap.recycle()
        }
        originalBitmap?.let {
            saveStateToHistory(it, regions, true, text)
        }
        updateRedactAllButtonState()
    }

    private fun applyRedactions() {
        val bitmap = originalBitmap ?: return
        val activeRegions = detectedRegions.filter { it.isRedacted }
        if (activeRegions.isEmpty()) {
            Toast.makeText(this, "No active regions to redact", Toast.LENGTH_SHORT).show()
            return
        }

        appliedRedactionsCount += activeRegions.size // Accumulate count

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val redacted = withContext(Dispatchers.Default) {
                redactBitmap(bitmap, activeRegions)
            }
            originalBitmap = redacted
            binding.imagePreview.setImageBitmap(redacted)
            binding.overlayView.clear()
            detectedRegions = emptyList()
            binding.summaryBadge.visibility = View.GONE
            binding.loadingIndicator.visibility = View.GONE

            // Save redacted state to history
            saveStateToHistory(redacted, emptyList(), false, null)
            updateRedactAllButtonState()
            Toast.makeText(this@PreviewActivity, "Redactions applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun redactBitmap(bitmap: Bitmap, regions: List<DetectedRegion>): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        // Read user preference configurations programmatically
        val prefs = getSharedPreferences("zenscan_prefs", MODE_PRIVATE)
        val redactionStyle = prefs.getString("redaction_style", "black") ?: "black"

        val paint = android.graphics.Paint().apply {
            this.style = android.graphics.Paint.Style.FILL
        }

        when (redactionStyle) {
            "black" -> {
                paint.color = android.graphics.Color.BLACK
            }
            "slate" -> {
                paint.color = "#1E293B".toColorInt()
            }
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
                } catch (e: Exception) {
                    // Fail-safe layout fallback to solid black blocks on extreme memory failures
                    val fallbackPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        this.style = android.graphics.Paint.Style.FILL
                    }
                    canvas.drawRoundRect(
                        left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                        12f, 12f, fallbackPaint
                    )
                }
            } else {
                canvas.drawRoundRect(
                    left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                    12f, 12f, paint
                )
            }
        }
        return result
    }

    // High performance 2-pass vertical/horizontal custom box blur pixel shader
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

    // Compress raw bitmap to clean JPEG, stripping source metadata, then share
    private fun exportAndShareImage() {
        val bitmap = originalBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No image to export", Toast.LENGTH_SHORT).show()
            return
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            var exceptionMessage: String? = null
            val sharedFile = withContext(Dispatchers.IO) {
                try {
                    val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }

                    // Clean up previous temporary exports to conserve user device space
                    sharedDir.listFiles()?.forEach { it.delete() }

                    val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")

                    // Load user's export compression preferences
                    val prefs = getSharedPreferences("zenscan_prefs", MODE_PRIVATE)
                    val quality = prefs.getInt("export_quality", 95)

                    FileOutputStream(file).use { out ->
                        // This native compression strips original camera & location EXIF metadata
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }

                    // Save a persistent copy in local secure history folder for bottom tab history view if not already opened from history
                    if (!isFromHistory) {
                        val historyDir = File(filesDir, "redacted_history").apply { mkdirs() }
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

            binding.loadingIndicator.visibility = View.GONE

            if (sharedFile != null) {
                try {
                    val contentUri = FileProvider.getUriForFile(
                        this@PreviewActivity,
                        "${packageName}.fileprovider",
                        sharedFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(shareIntent, "Share Redacted Image"))
                } catch (e: Exception) {
                    Toast.makeText(this@PreviewActivity, "Failed to share image: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this@PreviewActivity, exceptionMessage ?: "Failed to export image", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline.close()
        // Release bitmap history to conserve memory immediately
        historyStack.forEach { it.bitmap.recycle() }
        historyStack.clear()
    }
}
