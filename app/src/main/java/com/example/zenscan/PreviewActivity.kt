package com.example.zenscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.zenscan.databinding.ActivityPreviewBinding
import com.example.zenscan.detection.DetectionPipeline
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val pipeline = DetectionPipeline()
    private var originalBitmap: Bitmap? = null
    private var detectedRegions: List<DetectedRegion> = emptyList()

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
            Toast.makeText(this, "Undo pressed", Toast.LENGTH_SHORT).show()
        }
        binding.btnRedactAll.setOnClickListener {
            applyRedactions()
        }
        binding.btnBrush.setOnClickListener {
            Toast.makeText(this, "Brush tool pressed", Toast.LENGTH_SHORT).show()
        }
        binding.btnExport.setOnClickListener {
            Toast.makeText(this, "Export pressed", Toast.LENGTH_SHORT).show()
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

        if (total == 0) {
            binding.summaryBadge.text = getString(R.string.no_sensitive_found)
        } else {
            binding.summaryBadge.text = getString(R.string.found_sensitive, total)
        }
        binding.summaryBadge.visibility = View.VISIBLE
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

    override fun onDestroy() {
        super.onDestroy()
        pipeline.close()
    }
}
