package com.example.zenscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.zenscan.databinding.ActivityMainBinding
import com.example.zenscan.ui.RedactionHistoryAdapter
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: RedactionHistoryAdapter
    private lateinit var filesAdapter: RedactionHistoryAdapter

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

    private var tempCameraUri: Uri? = null

    // Request CAMERA permission contract
    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan physical documents", Toast.LENGTH_LONG).show()
        }
    }

    // Capture photo contract
    private val capturePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = tempCameraUri
            if (uri != null) {
                val intent = Intent(this, PreviewActivity::class.java).apply {
                    data = uri
                }
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "Camera capture cancelled or failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
    }

    override fun onResume() {
        super.onResume()
        // Always refresh history logs so they sync immediately on navigation return
        loadHistoryData()
    }

    private fun setupRecyclerViews() {
        homeAdapter = RedactionHistoryAdapter({ file ->
            openHistoryFile(file)
        }, { file ->
            promptDeleteFile(file)
        })
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.recentList.adapter = homeAdapter

        filesAdapter = RedactionHistoryAdapter({ file ->
            openHistoryFile(file)
        }, { file ->
            promptDeleteFile(file)
        })
        binding.fullHistoryList.layoutManager = LinearLayoutManager(this)
        binding.fullHistoryList.adapter = filesAdapter
    }

    private fun openHistoryFile(file: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(this, PreviewActivity::class.java).apply {
                data = contentUri
                putExtra("is_from_history", true)
                putExtra("history_file_name", file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClickListeners() {
        binding.btnGallery.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnCamera.setOnClickListener {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermission.launch(android.Manifest.permission.CAMERA)
            }
        }

        // Empty state CTA click triggers Photo Picker
        binding.btnEmptyStateAction.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                    loadHistoryData()
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
        val prefs = getSharedPreferences("zenscan_prefs", MODE_PRIVATE)

        // 1. Bind Redaction Style RadioGroup
        val activeStyle = prefs.getString("redaction_style", "black")
        when (activeStyle) {
            "black" -> binding.rbStyleBlack.isChecked = true
            "slate" -> binding.rbStyleSlate.isChecked = true
            "blur" -> binding.rbStyleBlur.isChecked = true
        }
        binding.rgRedactionStyle.setOnCheckedChangeListener { _, checkedId ->
            val styleValue = when (checkedId) {
                R.id.rbStyleBlack -> "black"
                R.id.rbStyleSlate -> "slate"
                R.id.rbStyleBlur -> "blur"
                else -> "black"
            }
            prefs.edit().putString("redaction_style", styleValue).apply()
        }

        // 2. Bind Export Quality RadioGroup
        val activeQuality = prefs.getInt("export_quality", 95)
        when (activeQuality) {
            95 -> binding.rbQualityHigh.isChecked = true
            85 -> binding.rbQualityMedium.isChecked = true
            70 -> binding.rbQualityLow.isChecked = true
        }
        binding.rgExportQuality.setOnCheckedChangeListener { _, checkedId ->
            val qualityValue = when (checkedId) {
                R.id.rbQualityHigh -> 95
                R.id.rbQualityMedium -> 85
                R.id.rbQualityLow -> 70
                else -> 95
            }
            prefs.edit().putInt("export_quality", qualityValue).apply()
        }

        // 3. Clear all logs button
        binding.btnClearCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Wipe Redacted History")
                .setMessage("Are you sure you want to permanently delete all redacted files from storage? This action cannot be undone.")
                .setPositiveButton("Wipe All") { _, _ ->
                    wipeAllHistoryFiles()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadHistoryData() {
        val historyDir = File(filesDir, "redacted_history")
        val files = if (historyDir.exists()) {
            historyDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("ZenScan_") && it.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            emptyList()
        }

        // Bind data models to both list levels
        homeAdapter.updateData(files.take(3))
        filesAdapter.updateData(files)

        // Tweak visibility configurations depending on count
        if (files.isEmpty()) {
            binding.emptyStateView.visibility = View.VISIBLE
            binding.fullHistoryList.visibility = View.GONE
        } else {
            binding.emptyStateView.visibility = View.GONE
            binding.fullHistoryList.visibility = View.VISIBLE
        }
    }

    private fun shareHistoryFile(file: File) {
        try {
            // Secure sharing solution: Copy persistent history file to the authorized shared cache folder first
            val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }
            // Clean up previous temporary exports to conserve user device space
            sharedDir.listFiles()?.forEach { it.delete() }

            val tempSharedFile = File(sharedDir, file.name)
            file.inputStream().use { input ->
                tempSharedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                tempSharedFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Redacted Image"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share image", Toast.LENGTH_SHORT).show()
        }
    }


    private fun launchCamera() {
        try {
            val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }
            val tempFile = File(sharedDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                tempFile
            )
            tempCameraUri = contentUri
            capturePhoto.launch(contentUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to initialize camera storage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptDeleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete History Item")
            .setMessage("Do you want to permanently delete this redacted photo from your offline history?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Item deleted", Toast.LENGTH_SHORT).show()
                    loadHistoryData()
                } else {
                    Toast.makeText(this, "Failed to delete item", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wipeAllHistoryFiles() {
        val historyDir = File(filesDir, "redacted_history")
        if (historyDir.exists()) {
            historyDir.listFiles()?.forEach { it.delete() }
        }
        val cacheDirShared = File(cacheDir, "shared_images")
        if (cacheDirShared.exists()) {
            cacheDirShared.listFiles()?.forEach { it.delete() }
        }

        Toast.makeText(this, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
        loadHistoryData()
    }
}