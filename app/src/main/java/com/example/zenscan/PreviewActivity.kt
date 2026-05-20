package com.example.zenscan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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

    // State definition for RAM-safe Undo History
    private data class RedactionState(
        val bitmap: Bitmap,
        val regions: List<DetectedRegion>,
        val isBadgeVisible: Boolean,
        val badgeText: String?
    )

    private val historyStack = mutableListOf<RedactionState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupToolbarButtons()

        val uri = intent.data
        if (uri != null) {
            loadImageAndDetect(uri)
        } else {
            Toast.makeText(this, R.string.no_image_provided, Toast.LENGTH_SHORT).show()
            finish()
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
            Toast.makeText(this, "Brush tool coming soon!", Toast.LENGTH_SHORT).show()
        }
        binding.btnExport.setOnClickListener {
            exportAndShareImage()
        }

        // Initialize undo button state
        updateUndoButtonState()
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
            historyStack.add(RedactionState(bmpCopy, regions.toList(), isBadgeVisible, badgeText))
            
            // Limit memory usage by restricting undo depth
            if (historyStack.size > 15) {
                historyStack.removeAt(0)
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

    private fun performUndo() {
        if (historyStack.size > 1) {
            // Remove the current active state
            historyStack.removeAt(historyStack.lastIndex)

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
            } else {
                binding.summaryBadge.visibility = View.GONE
            }

            updateUndoButtonState()
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
            
            // Save initial loaded image state
            saveStateToHistory(bitmap, emptyList(), false, null)
            runDetection(bitmap)
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
            getString(R.string.found_sensitive, total)
        }
        binding.summaryBadge.text = text
        binding.summaryBadge.visibility = View.VISIBLE

        // Overwrite the initial history state with the completed scanned details,
        // so that the first point of undo returns to the scanned bounding box state.
        if (historyStack.isNotEmpty()) {
            historyStack.removeAt(historyStack.lastIndex)
        }
        originalBitmap?.let {
            saveStateToHistory(it, regions, true, text)
        }
    }

    private fun applyRedactions() {
        val bitmap = originalBitmap ?: return
        if (detectedRegions.isEmpty()) {
            Toast.makeText(this, R.string.no_sensitive_found, Toast.LENGTH_SHORT).show()
            return
        }
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val redacted = withContext(Dispatchers.Default) {
                redactBitmap(bitmap, detectedRegions)
            }
            originalBitmap = redacted
            binding.imagePreview.setImageBitmap(redacted)
            binding.overlayView.clear()
            binding.summaryBadge.visibility = View.GONE
            binding.loadingIndicator.visibility = View.GONE
            
            // Save redacted state to history
            saveStateToHistory(redacted, emptyList(), false, null)
            Toast.makeText(this@PreviewActivity, "Redactions applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun redactBitmap(bitmap: Bitmap, regions: List<DetectedRegion>): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.FILL
        }
        for (region in regions) {
            val r = region.boundingBox
            canvas.drawRoundRect(
                r.left.toFloat(), r.top.toFloat(),
                r.right.toFloat(), r.bottom.toFloat(),
                12f, 12f, paint
            )
        }
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
            val sharedFile = withContext(Dispatchers.IO) {
                try {
                    val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }
                    
                    // Clean up previous exports to conserve user device memory
                    sharedDir.listFiles()?.forEach { it.delete() }
                    
                    val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        // This native compression strips original camera & location EXIF metadata
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    file
                } catch (e: Exception) {
                    null
                }
            }

            binding.loadingIndicator.visibility = View.GONE

            if (sharedFile != null) {
                try {
                    val contentUri = FileProvider.getUriForFile(
                        this@PreviewActivity,
                        "com.example.zenscan.fileprovider",
                        sharedFile
                    )
                    
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    startActivity(Intent.createChooser(shareIntent, "Share Redacted Image"))
                } catch (e: Exception) {
                    Toast.makeText(this@PreviewActivity, "Failed to share image", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@PreviewActivity, "Failed to export image", Toast.LENGTH_SHORT).show()
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
