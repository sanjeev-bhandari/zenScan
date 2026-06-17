package com.example.zenscan

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: RedactionHistoryAdapter
    private lateinit var filesAdapter: RedactionHistoryAdapter

    private val viewModel: MainViewModel by viewModels {
        ViewModelFactory(application, (application as ZenScanApplication).container)
    }

    // Modern Photo Picker launcher
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val intent = Intent(this, PreviewActivity::class.java).apply {
                data = uri
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    // Fallback Photo Picker launcher for older/custom devices
    private val pickMediaFallback = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = Intent(this, PreviewActivity::class.java).apply {
                data = uri
            }
            startActivity(intent)
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
            // tempCameraUri was sent to capturePhoto, which is stored in the viewModel's prepared Uri
            // Wait, we need to pass the uri of the capture to PreviewActivity.
            // Let's get the uri that we prepared. We can track it or retrieve it.
            // Let's pass the tempCameraUri when we launch.
            tempCameraUri?.let { uri ->
                val intent = Intent(this, PreviewActivity::class.java).apply {
                    data = uri
                }
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "Camera capture cancelled or failed", Toast.LENGTH_SHORT).show()
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
                val intent = Intent(this, PreviewActivity::class.java).apply {
                    data = page.imageUri
                }
                startActivity(intent)
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
        // Apply saved theme settings before super.onCreate to prevent flickering/state issues
        val container = (application as ZenScanApplication).container
        val themeMode = container.preferencesManager.getThemeMode()
        val targetMode = when (themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(targetMode)

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle WindowInsets to prevent top Status Bar overlap on header bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { view, windowInsets ->
            val statusInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val originalTopPadding = (18 * resources.displayMetrics.density).toInt()
            view.setPadding(
                view.paddingLeft,
                statusInsets.top + originalTopPadding,
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
        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("image/")) {
                val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (imageUri != null) {
                    val previewIntent = Intent(this, PreviewActivity::class.java).apply {
                        data = imageUri
                    }
                    startActivity(previewIntent)
                } else {
                    Toast.makeText(this, "Failed to retrieve shared image", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Wipe Redacted History")
                .setMessage("Are you sure you want to permanently delete all redacted files from storage? This action cannot be undone.")
                .setPositiveButton("Wipe All") { _, _ ->
                    viewModel.wipeAllHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Sync theme RadioButton checked states to reflect the active theme value. */
    private fun syncThemeSelection(themeMode: String) {
        binding.rbThemeSystem.isChecked = themeMode == "system"
        binding.rbThemeLight.isChecked = themeMode == "light"
        binding.rbThemeDark.isChecked = themeMode == "dark"
    }

    /** Sync redaction style RadioButton checked states to reflect the active style value. */
    private fun syncRedactionSelection(redactionStyle: String) {
        binding.rbStyleBlack.isChecked = redactionStyle == "black"
        binding.rbStyleSlate.isChecked = redactionStyle == "slate"
        binding.rbStyleBlur.isChecked = redactionStyle == "blur"
        binding.rbStylePixelate.isChecked = redactionStyle == "pixelate"
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        // Update lists
                        homeAdapter.updateData(state.recentFiles)
                        filesAdapter.updateData(state.allFiles)

                        // Update visibility
                        if (state.isHistoryEmpty) {
                            binding.emptyStateView.visibility = View.VISIBLE
                            binding.fullHistoryList.visibility = View.GONE
                        } else {
                            binding.emptyStateView.visibility = View.GONE
                            binding.fullHistoryList.visibility = View.VISIBLE
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
}