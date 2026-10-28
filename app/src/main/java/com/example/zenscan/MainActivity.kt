package com.example.zenscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zenscan.databinding.ActivityMainBinding
import com.example.zenscan.ui.RedactionHistoryAdapter
import com.example.zenscan.viewmodel.MainUiEvent
import com.example.zenscan.viewmodel.MainViewModel
import com.example.zenscan.viewmodel.ViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: RedactionHistoryAdapter
    private lateinit var filesAdapter: RedactionHistoryAdapter

    private val viewModel: MainViewModel by viewModels {
        ViewModelFactory(application, (application as ZenScanApplication).container)
    }

    private fun openPreview(uri: Uri) {
        startActivity(Intent(this, PreviewActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // Modern Photo Picker launcher
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            openPreview(uri)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    // Fallback Photo Picker launcher for older/custom devices
    private val pickMediaFallback = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            openPreview(uri)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPhotoPicker() {
        try {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            try {
                pickMediaFallback.launch("image/*")
            } catch (ex: Exception) {
                Toast.makeText(this, "No photo picker application found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Request CAMERA permission contract
    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            viewModel.prepareCameraCapture()
        } else {
            Toast.makeText(this, "Camera permission is required to scan physical documents", Toast.LENGTH_LONG).show()
        }
    }

    // Capture photo contract
    private val capturePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempCameraUri?.let { openPreview(it) }
        } else {
            tempCameraUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // Ignore cleanup failure
                }
            }
            Toast.makeText(this, "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private var tempCameraUri: Uri? = null

    // Document scanner launcher for perspective correction & edge snapping
    private val docScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val page = scanningResult?.pages?.firstOrNull()
            if (page != null) {
                openPreview(page.imageUri)
            } else {
                Toast.makeText(this, "No scanned page found", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Document scan cancelled or failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                docScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                // Fallback to standard camera if GMS Document Scanner is unavailable (e.g. no Play Services)
                Toast.makeText(this, "Document Scanner unavailable, falling back to camera...", Toast.LENGTH_SHORT).show()
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    viewModel.prepareCameraCapture()
                } else {
                    requestCameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseTopPadding = resources.getDimensionPixelSize(R.dimen.header_padding_vertical)
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { view, windowInsets ->
            val statusInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft,
                statusInsets.top + baseTopPadding,
                view.paddingRight,
                view.paddingBottom
            )
            windowInsets
        }

        // Handle WindowInsets for bottom navigation view to clear gesture pill perfectly
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navInsets.bottom
            )
            windowInsets
        }

        setupRecyclerViews()
        setupClickListeners()
        setupNavigation()
        setupSettingsPanel()
        observeViewModel()

        // Handle system back navigation to return to Home tab if on a secondary tab
        onBackPressedDispatcher.addCallback(this) {
            if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
                binding.bottomNavigation.selectedItemId = R.id.nav_home
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Restore bottom navigation tab state (crucial for dynamic theme recreation)
        val restoredNavId = savedInstanceState?.getInt("selected_nav_id") ?: R.id.nav_home
        binding.bottomNavigation.selectedItemId = restoredNavId

        // Handle incoming share intents (Receive images shared from other apps)
        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) {
            outState.putInt("selected_nav_id", binding.bottomNavigation.selectedItemId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.data
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

            if (imageUri != null) {
                stageSharedImageAndLaunch(imageUri)
            } else {
                Toast.makeText(this, "Failed to retrieve shared image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stageSharedImageAndLaunch(sourceUri: Uri) {
        val targetUri = try {
            val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }
            val targetFile = File(sharedDir, "shared_${System.currentTimeMillis()}.jpg")
            val copied = contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output) > 0
                }
            } ?: false

            if (copied && targetFile.exists() && targetFile.length() > 0) {
                FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.file_provider",
                    targetFile
                )
            } else {
                sourceUri
            }
        } catch (e: Exception) {
            sourceUri
        }

        openPreview(targetUri)
    }

    override fun onResume() {
        super.onResume()
        // Always refresh history logs so they sync immediately on navigation return
        viewModel.loadHistoryData()
    }

    private fun setupRecyclerViews() {
        val preferencesManager = (application as ZenScanApplication).container.preferencesManager
        homeAdapter = RedactionHistoryAdapter(preferencesManager, { file ->
            viewModel.onFileClicked(file)
        }, { file ->
            promptDeleteFile(file)
        })
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.recentList.adapter = homeAdapter

        filesAdapter = RedactionHistoryAdapter(preferencesManager, { file ->
            viewModel.onFileClicked(file)
        }, { file ->
            promptDeleteFile(file)
        })
        binding.fullHistoryList.layoutManager = LinearLayoutManager(this)
        binding.fullHistoryList.adapter = filesAdapter
    }

    private fun setupClickListeners() {
        binding.btnGallery.setOnClickListener {
            launchPhotoPicker()
        }

        binding.btnCamera.setOnClickListener {
            startDocumentScanner()
        }

        // Empty state CTA click triggers Photo Picker
        binding.btnEmptyStateAction.setOnClickListener {
            launchPhotoPicker()
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.headerTitle.text = getString(R.string.app_name)
                    binding.homeContainer.visibility = View.VISIBLE
                    binding.filesContainer.visibility = View.GONE
                    binding.settingsContainer.visibility = View.GONE
                    true
                }
                R.id.nav_files -> {
                    binding.headerTitle.text = getString(R.string.processed_files_title)
                    binding.homeContainer.visibility = View.GONE
                    binding.filesContainer.visibility = View.VISIBLE
                    binding.settingsContainer.visibility = View.GONE
                    viewModel.loadHistoryData()
                    true
                }
                R.id.nav_settings -> {
                    binding.headerTitle.text = getString(R.string.settings_title)
                    binding.homeContainer.visibility = View.GONE
                    binding.filesContainer.visibility = View.GONE
                    binding.settingsContainer.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSettingsPanel() {
        // Theme row click listeners (custom rows replace RadioGroup)
        binding.rowThemeSystem.setOnClickListener { viewModel.updateThemeMode("system") }
        binding.rowThemeLight.setOnClickListener { viewModel.updateThemeMode("light") }
        binding.rowThemeDark.setOnClickListener { viewModel.updateThemeMode("dark") }

        // Redaction style row click listeners
        binding.rowStyleBlack.setOnClickListener { viewModel.updateRedactionStyle("black") }
        binding.rowStyleSlate.setOnClickListener { viewModel.updateRedactionStyle("slate") }
        binding.rowStyleBlur.setOnClickListener { viewModel.updateRedactionStyle("blur") }
        binding.rowStylePixelate.setOnClickListener { viewModel.updateRedactionStyle("pixelate") }

        binding.rgExportQuality.setOnCheckedChangeListener { _, checkedId ->
            val qualityValue = when (checkedId) {
                R.id.rbQualityHigh -> 95
                R.id.rbQualityMedium -> 85
                R.id.rbQualityLow -> 70
                else -> 95
            }
            viewModel.updateExportQuality(qualityValue)
        }

        binding.btnClearCache.setOnClickListener {
            val fileCount = viewModel.uiState.value.allFiles.size
            val title = if (fileCount > 0) {
                "Delete $fileCount history files?"
            } else {
                "Delete history files?"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage("This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.wipeAllHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.cardCustomRules.setOnClickListener {
            showCustomFiltersDialog()
        }
    }

    /** Sync theme RadioButton checked states and row selection highlights. */
    private fun syncThemeSelection(themeMode: String) {
        val isSystem = themeMode == "system"
        val isLight = themeMode == "light"
        val isDark = themeMode == "dark"

        binding.rbThemeSystem.isChecked = isSystem
        binding.rbThemeLight.isChecked = isLight
        binding.rbThemeDark.isChecked = isDark

        binding.rowThemeSystem.isSelected = isSystem
        binding.rowThemeLight.isSelected = isLight
        binding.rowThemeDark.isSelected = isDark
    }

    /** Sync redaction style RadioButton checked states and row selection highlights. */
    private fun syncRedactionSelection(redactionStyle: String) {
        val isBlack = redactionStyle == "black"
        val isSlate = redactionStyle == "slate"
        val isBlur = redactionStyle == "blur"
        val isPixelate = redactionStyle == "pixelate"

        binding.rbStyleBlack.isChecked = isBlack
        binding.rbStyleSlate.isChecked = isSlate
        binding.rbStyleBlur.isChecked = isBlur
        binding.rbStylePixelate.isChecked = isPixelate

        binding.rowStyleBlack.isSelected = isBlack
        binding.rowStyleSlate.isSelected = isSlate
        binding.rowStyleBlur.isSelected = isBlur
        binding.rowStylePixelate.isSelected = isPixelate
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        // Update lists
                        homeAdapter.submitList(state.recentFiles)
                        filesAdapter.submitList(state.allFiles)

                        // Update visibility
                        if (state.isHistoryEmpty) {
                            binding.emptyStateView.visibility = View.VISIBLE
                            binding.fullHistoryList.visibility = View.GONE
                            binding.recentEmptyState.visibility = View.VISIBLE
                            binding.recentList.visibility = View.GONE
                        } else {
                            binding.emptyStateView.visibility = View.GONE
                            binding.fullHistoryList.visibility = View.VISIBLE
                            binding.recentEmptyState.visibility = View.GONE
                            binding.recentList.visibility = View.VISIBLE
                        }

                        // Sync active settings UI check-states
                        syncThemeSelection(state.themeMode)
                        syncRedactionSelection(state.redactionStyle)

                        // Apply default system/theme values instantly if updated from state
                        val targetMode = when (state.themeMode) {
                            "light" -> AppCompatDelegate.MODE_NIGHT_NO
                            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                            AppCompatDelegate.setDefaultNightMode(targetMode)
                        }

                        when (state.exportQuality) {
                            95 -> if (!binding.rbQualityHigh.isChecked) binding.rbQualityHigh.isChecked = true
                            85 -> if (!binding.rbQualityMedium.isChecked) binding.rbQualityMedium.isChecked = true
                            70 -> if (!binding.rbQualityLow.isChecked) binding.rbQualityLow.isChecked = true
                        }
                    }
                }

                launch {
                    viewModel.event.collect { event ->
                        when (event) {
                            is MainUiEvent.ShowToast -> {
                                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
                            is MainUiEvent.OpenHistoryFile -> {
                                val intent = Intent(this@MainActivity, PreviewActivity::class.java).apply {
                                    data = event.uri
                                    putExtra("is_from_history", true)
                                    putExtra("history_file_name", event.file.name)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(intent)
                            }
                            is MainUiEvent.LaunchCamera -> {
                                tempCameraUri = event.uri
                                capturePhoto.launch(event.uri)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun promptDeleteFile(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete History Item")
            .setMessage("Do you want to permanently delete this redacted photo from your offline history?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteFile(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomFiltersDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_filters, null)
        val container = dialogView.findViewById<android.widget.LinearLayout>(R.id.rulesContainer)
        val emptyText = dialogView.findViewById<android.widget.TextView>(R.id.tvEmptyRules)
        val btnAdd = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddFilter)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAdd.setOnClickListener {
            dialog.dismiss()
            showAddCustomFilterDialog()
        }

        fun populateRules() {
            container.removeAllViews()
            val rules = viewModel.uiState.value.customRules
            if (rules.isEmpty()) {
                emptyText.visibility = View.VISIBLE
            } else {
                emptyText.visibility = View.GONE
                for (rule in rules) {
                    val itemView = layoutInflater.inflate(R.layout.item_custom_filter, container, false)
                    val tvLabel = itemView.findViewById<android.widget.TextView>(R.id.tvFilterLabel)
                    val tvPattern = itemView.findViewById<android.widget.TextView>(R.id.tvFilterPattern)
                    val tvBadge = itemView.findViewById<android.widget.TextView>(R.id.tvFilterTypeBadge)
                    val btnDelete = itemView.findViewById<android.widget.ImageButton>(R.id.btnDeleteRule)

                    tvLabel.text = rule.label
                    tvPattern.text = rule.pattern
                    tvBadge.text = if (rule.isRegex) "REGEX" else "KEYWORD"

                    btnDelete.setOnClickListener {
                        viewModel.deleteCustomRule(rule.id)
                        populateRules()
                    }
                    container.addView(itemView)
                }
            }
        }

        populateRules()
        dialog.show()
    }

    private fun showAddCustomFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_custom_filter, null)
        val etPattern = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPattern)
        val etLabel = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etLabel)
        val rgFilterType = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgFilterType)
        val tvValidationError = dialogView.findViewById<android.widget.TextView>(R.id.tvValidationError)
        val btnConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirmAdd)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelAdd)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
            showCustomFiltersDialog()
        }

        btnConfirm.setOnClickListener {
            val pattern = etPattern.text?.toString() ?: ""
            val label = etLabel.text?.toString() ?: ""
            val isRegex = rgFilterType.checkedRadioButtonId == R.id.rbTypeRegex

            if (pattern.trim().isEmpty()) {
                tvValidationError.text = getString(R.string.error_empty_pattern)
                tvValidationError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (isRegex) {
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    tvValidationError.text = getString(R.string.error_invalid_regex)
                    tvValidationError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
            }

            viewModel.addCustomRule(pattern, isRegex, label)
            dialog.dismiss()
            showCustomFiltersDialog()
        }

        dialog.show()
    }
}