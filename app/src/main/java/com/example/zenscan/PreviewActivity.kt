package com.example.zenscan

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.zenscan.databinding.ActivityPreviewBinding
import com.example.zenscan.viewmodel.PreviewUiEvent
import com.example.zenscan.viewmodel.PreviewViewModel
import com.example.zenscan.viewmodel.ViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private var exifDialog: android.app.Dialog? = null

    private val viewModel: PreviewViewModel by viewModels {
        ViewModelFactory(application, (application as ZenScanApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and recent switcher leaks
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Handle WindowInsets to prevent top Status Bar overlap on toolbar dynamically
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val statusInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusInsets.top, 0, 0)
            windowInsets
        }

        setupToolbarButtons()
        setupOverlayCallbacks()
        observeViewModel()

        val uri = intent.data
        val isFromHistory = intent.getBooleanExtra("is_from_history", false)
        val historyFileName = intent.getStringExtra("history_file_name")

        supportActionBar?.title = if (isFromHistory) "Redacted History" else "Smart Scan"

        if (uri != null) {
            viewModel.initImage(uri, isFromHistory, historyFileName)
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
            R.id.action_rescan -> {
                viewModel.rescanImage()
                true
            }
            R.id.action_save -> {
                viewModel.saveToGallery()
                true
            }
            R.id.action_strip_metadata -> {
                viewModel.exportAndShareImage(showMetadataToast = true)
                true
            }
            R.id.action_placeholder -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("About Safe Share")
                    .setMessage("ZenScan Privacy Redactor uses state-of-the-art on-device AI models to find and censor sensitive details like faces, credit cards, and emails. Everything runs 100% offline inside your device's sandboxed environment, keeping your personal data completely private.")
                    .setPositiveButton("Got It", null)
                    .show()
                true
            }
            R.id.action_view_exif -> {
                viewModel.showExifMetadata(true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbarButtons() {
        binding.btnUndo.setOnClickListener {
            viewModel.performUndo()
        }
        binding.btnRedactAll.setOnClickListener {
            viewModel.applyRedactions()
        }
        binding.btnBrush.setOnClickListener {
            viewModel.toggleBrushMode()
        }
        binding.btnExport.setOnClickListener {
            viewModel.exportAndShareImage()
        }

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
            viewModel.toggleRegionRedaction(region)
        }
        binding.overlayView.onBrushStrokeCompleted = { path, strokeWidth ->
            viewModel.bakeBrushStroke(path, strokeWidth)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        // 1. Loading Indicator
                        binding.loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                        // 2. Brush mode configurations (white circle + dark icon when active, matching mockup)
                        binding.overlayView.isBrushMode = state.isBrushMode
                        binding.btnBrush.isSelected = state.isBrushMode
                        binding.brushModeBanner.visibility = if (state.isBrushMode) View.VISIBLE else View.GONE
                        val brushIconColor = if (state.isBrushMode) {
                            ContextCompat.getColor(this@PreviewActivity, R.color.bg_dark)
                        } else {
                            ContextCompat.getColor(this@PreviewActivity, R.color.text_dark_primary)
                        }
                        ImageViewCompat.setImageTintList(binding.btnBrush, ColorStateList.valueOf(brushIconColor))
                        binding.tvBrushLabel.setTextColor(
                            if (state.isBrushMode) {
                                ContextCompat.getColor(this@PreviewActivity, R.color.white)
                            } else {
                                0xFF9AA0A6.toInt()
                            }
                        )

                        // 3. Image display and regions overlay
                        val bmp = state.bitmap
                        binding.overlayView.bitmap = bmp
                        if (bmp != null) {
                            if (state.detectedRegions.isNotEmpty()) {
                                binding.overlayView.setRegions(state.detectedRegions, bmp.width, bmp.height, animate = false)
                            } else {
                                binding.overlayView.clear()
                            }
                        }

                        // 4. Undo and Redact All button states
                        binding.btnUndo.isEnabled = state.undoAvailable
                        binding.btnUndo.alpha = if (state.undoAvailable) 1.0f else 0.4f

                        binding.btnRedactAll.isEnabled = state.redactAllAvailable
                        binding.btnRedactAll.alpha = if (state.redactAllAvailable) 1.0f else 0.4f

                        // 5. Toolbar detection summary subtitle
                        if (state.isSummaryBadgeVisible && !state.summaryBadgeText.isNullOrEmpty()) {
                            updateToolbarSubtitle(state.summaryBadgeText, isVisible = true, isRoseColor = state.isSummaryBadgeRoseColor)
                        } else {
                            updateToolbarSubtitle(null, isVisible = false)
                        }

                        // 6. EXIF dialog visibility
                        if (state.showExifDialog) {
                            showExifMetadataDialog(state.exifMetadata)
                        } else {
                            exifDialog?.dismiss()
                            exifDialog = null
                        }
                    }
                }

                launch {
                    viewModel.event.collect { event ->
                        when (event) {
                            is PreviewUiEvent.ShowToast -> {
                                Toast.makeText(this@PreviewActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
                            is PreviewUiEvent.ShareImage -> {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, event.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Share Redacted Image"))
                            }
                            is PreviewUiEvent.SaveSuccess -> {
                                Toast.makeText(this@PreviewActivity, "Saved to gallery: ${event.path}", Toast.LENGTH_LONG).show()
                            }
                            is PreviewUiEvent.FinishActivity -> {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showExifMetadataDialog(metadata: List<Pair<String, String>>?) {
        if (exifDialog != null && exifDialog!!.isShowing) return
        
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exif_dialog_title)
            .setPositiveButton(R.string.exif_close) { _, _ ->
                viewModel.showExifMetadata(false)
            }
            .setOnDismissListener {
                viewModel.showExifMetadata(false)
            }

        if (metadata.isNullOrEmpty()) {
            val view = layoutInflater.inflate(R.layout.dialog_exif_empty, null)
            builder.setView(view)
        } else {
            val view = layoutInflater.inflate(R.layout.dialog_exif_list, null)
            val container = view.findViewById<android.widget.LinearLayout>(R.id.metadata_container)
            val subtitle = view.findViewById<android.widget.TextView>(R.id.exif_subtitle)
            subtitle.text = getString(R.string.exif_dialog_subtitle)
            
            for ((label, value) in metadata) {
                val itemView = layoutInflater.inflate(R.layout.item_exif_tag, container, false)
                val textLabel = itemView.findViewById<android.widget.TextView>(R.id.tag_label)
                val textValue = itemView.findViewById<android.widget.TextView>(R.id.tag_value)
                textLabel.text = label
                textValue.text = value
                container.addView(itemView)
            }
            builder.setView(view)
        }

        exifDialog = builder.show()
    }

    private fun updateToolbarSubtitle(text: String?, isVisible: Boolean, isRoseColor: Boolean = true) {
        if (isVisible && !text.isNullOrEmpty()) {
            supportActionBar?.subtitle = text
            val colorRes = if (isRoseColor) R.color.sensitive_alert_subtitle else R.color.protected_success_subtitle
            binding.toolbar.setSubtitleTextColor(ContextCompat.getColor(this, colorRes))
        } else {
            supportActionBar?.subtitle = null
        }
    }
}
