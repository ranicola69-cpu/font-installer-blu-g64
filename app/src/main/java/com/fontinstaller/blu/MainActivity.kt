package com.fontinstaller.blu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fontinstaller.blu.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFontUri: Uri? = null
    private var selectedFontName: String = ""
    private var isShizukuAvailable = false
    private var isShizukuPermissionGranted = false

    private val SHIZUKU_REQUEST_CODE = 100

    // Shizuku permission result listener
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            updateShizukuStatus()
            if (isShizukuPermissionGranted) {
                showStatus("Shizuku permission granted!", true)
            } else {
                showStatus("Shizuku permission denied", false)
            }
        }
    }

    // Shizuku binder received listener
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        isShizukuAvailable = true
        checkShizukuPermission()
        updateShizukuStatus()
    }

    // Shizuku binder dead listener
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        isShizukuAvailable = false
        isShizukuPermissionGranted = false
        updateShizukuStatus()
    }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedFont(it) }
    }

    // Permission launcher for storage
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openFilePicker()
        } else {
            showStatus("Storage permission required to select fonts", false)
        }
    }

    // Manage storage permission for Android 11+
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            openFilePicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShizuku()
        setupClickListeners()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        // Handle TTF file opened from file manager
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            handleSelectedFont(intent.data!!)
        }
    }

    private fun setupShizuku() {
        // Add listeners
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        // Check initial state
        try {
            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true
                checkShizukuPermission()
            }
        } catch (e: Exception) {
            isShizukuAvailable = false
        }
        updateShizukuStatus()
    }

    private fun checkShizukuPermission() {
        if (!isShizukuAvailable) return

        try {
            isShizukuPermissionGranted = if (Shizuku.isPreV11()) {
                // Pre-v11 doesn't need permission check this way
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            isShizukuPermissionGranted = false
        }
    }

    private fun requestShizukuPermission() {
        if (!isShizukuAvailable) {
            showShizukuNotRunningDialog()
            return
        }

        try {
            if (Shizuku.isPreV11()) {
                // Shizuku pre-v11, show message
                showStatus("Please update Shizuku to v11 or later", false)
            } else if (!isShizukuPermissionGranted) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (e: Exception) {
            showStatus("Error requesting Shizuku permission: ${e.message}", false)
        }
    }

    private fun updateShizukuStatus() {
        runOnUiThread {
            if (isShizukuAvailable && isShizukuPermissionGranted) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
                binding.shizukuStatusText.text = getString(R.string.shizuku_connected)
                binding.btnInstallFont.isEnabled = selectedFontUri != null
            } else if (isShizukuAvailable) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
                binding.shizukuStatusText.text = "Shizuku Running - Permission Required"
                binding.btnInstallFont.isEnabled = false
            } else {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
                binding.shizukuStatusText.text = getString(R.string.shizuku_disconnected)
                binding.btnInstallFont.isEnabled = false
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectFont.setOnClickListener {
            checkStoragePermissionAndOpenPicker()
        }

        binding.btnInstallFont.setOnClickListener {
            if (!isShizukuPermissionGranted) {
                requestShizukuPermission()
            } else {
                selectedFontUri?.let { installFont(it) }
            }
        }

        binding.btnOpenShizuku.setOnClickListener {
            if (!isShizukuAvailable) {
                openShizukuApp()
            } else if (!isShizukuPermissionGranted) {
                requestShizukuPermission()
            } else {
                openShizukuApp()
            }
        }
    }

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                // Shizuku not installed, open Play Store
                val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                try {
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    // Play Store not available, open web
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                }
            }
        } catch (e: Exception) {
            showStatus("Could not open Shizuku: ${e.message}", false)
        }
    }

    private fun checkStoragePermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            if (Environment.isExternalStorageManager()) {
                openFilePicker()
            } else {
                // Request manage all files permission
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission")
                    .setMessage("This app needs access to your files to select fonts. Please grant the permission on the next screen.")
                    .setPositiveButton("OK") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val allGranted = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                openFilePicker()
            } else {
                storagePermissionLauncher.launch(permissions)
            }
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun handleSelectedFont(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            if (!fileName.lowercase().endsWith(".ttf") && !fileName.lowercase().endsWith(".otf")) {
                showStatus("Please select a valid TTF or OTF font file", false)
                return
            }

            selectedFontUri = uri
            selectedFontName = fileName

            binding.selectedFontName.text = fileName
            binding.selectedFontPath.text = uri.path ?: uri.toString()

            // Try to load and preview the font
            loadFontPreview(uri)

            // Enable install button if Shizuku is ready
            binding.btnInstallFont.isEnabled = isShizukuAvailable && isShizukuPermissionGranted

            showStatus("Font selected: $fileName", true)
        } catch (e: Exception) {
            showStatus("Error reading font file: ${e.message}", false)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun loadFontPreview(uri: Uri) {
        try {
            // Copy font to a temp file to load as Typeface
            val tempFile = File(cacheDir, "temp_font.ttf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val typeface = Typeface.createFromFile(tempFile)
            binding.fontPreviewText.typeface = typeface
            binding.previewCard.visibility = View.VISIBLE

            tempFile.delete()
        } catch (e: Exception) {
            binding.previewCard.visibility = View.GONE
            // Font preview failed, but continue anyway
        }
    }

    private fun installFont(uri: Uri) {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) {
            showStatus("Shizuku not ready. Please ensure Shizuku is running and permission is granted.", false)
            return
        }

        showProgress(true)
        showStatus("Installing font...", true)

        Thread {
            try {
                // Copy font to a local file first
                val fontFile = File(cacheDir, "font_to_install.ttf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(fontFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Use Shizuku shell to copy font to system fonts directory
                val result = executeShizukuCommand(fontFile)

                runOnUiThread {
                    showProgress(false)
                    if (result.success) {
                        showSuccessDialog()
                    } else {
                        showStatus("Installation failed: ${result.message}", false)
                    }
                }

                fontFile.delete()

            } catch (e: Exception) {
                runOnUiThread {
                    showProgress(false)
                    showStatus("Error: ${e.message}", false)
                }
            }
        }.start()
    }

    private data class CommandResult(val success: Boolean, val message: String)

    private fun executeShizukuCommand(fontFile: File): CommandResult {
        return try {
            val fontName = selectedFontName.replace(" ", "_")
            val targetPath = "/data/fonts/files/0/$fontName"
            
            // Method 1: Try copying to /data/fonts (Android 12+)
            var process = Shizuku.newProcess(arrayOf("sh", "-c", "mkdir -p /data/fonts/files/0"), null, null)
            process.waitFor()
            
            // Copy the font file using cat
            val sourcePath = fontFile.absolutePath
            process = Shizuku.newProcess(arrayOf("sh", "-c", "cat '$sourcePath' > '$targetPath'"), null, null)
            var exitCode = process.waitFor()
            
            if (exitCode == 0) {
                // Set permissions
                process = Shizuku.newProcess(arrayOf("sh", "-c", "chmod 644 '$targetPath'"), null, null)
                process.waitFor()
                
                // Try to update font config
                updateFontConfig(fontName, targetPath)
                
                return CommandResult(true, "Font copied to system. Reboot required.")
            }
            
            // Method 2: Try /system/fonts (may fail without root remount)
            val systemFontPath = "/system/fonts/$fontName"
            process = Shizuku.newProcess(arrayOf("sh", "-c", "mount -o rw,remount /system 2>/dev/null; cat '$sourcePath' > '$systemFontPath'; chmod 644 '$systemFontPath'"), null, null)
            exitCode = process.waitFor()
            
            if (exitCode == 0) {
                return CommandResult(true, "Font installed to /system/fonts. Reboot required.")
            }
            
            // Method 3: Copy to accessible location and provide instructions
            val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/$fontName"
            fontFile.copyTo(File(downloadPath), overwrite = true)
            
            CommandResult(true, "Font copied to Downloads. Use a file manager with Shizuku to install manually, or try MT Manager method.")
            
        } catch (e: Exception) {
            CommandResult(false, e.message ?: "Unknown error")
        }
    }

    private fun updateFontConfig(fontName: String, fontPath: String) {
        try {
            // Try to update font fallback config (Android 12+)
            val configContent = """
                <?xml version="1.0" encoding="utf-8"?>
                <fonts>
                    <family name="custom-font">
                        <font weight="400" style="normal">$fontPath</font>
                    </family>
                </fonts>
            """.trimIndent()
            
            val configFile = File(cacheDir, "custom_fonts.xml")
            configFile.writeText(configContent)
            
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "cat '${configFile.absolutePath}' > /data/fonts/config/custom_fonts.xml 2>/dev/null"),
                null, null
            )
            process.waitFor()
            
            configFile.delete()
        } catch (e: Exception) {
            // Config update failed, continue anyway
        }
    }

    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Font Installed")
            .setMessage(getString(R.string.reboot_required))
            .setPositiveButton("Reboot Now") { _, _ ->
                try {
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", "reboot"), null, null)
                    process.waitFor()
                } catch (e: Exception) {
                    showStatus("Could not reboot automatically. Please reboot manually.", false)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showShizukuNotRunningDialog() {
        AlertDialog.Builder(this)
            .setTitle("Shizuku Not Running")
            .setMessage("Shizuku service is not running. Please:\n\n1. Open Shizuku app\n2. Enable Wireless Debugging in Developer Options\n3. Pair using the pairing code\n4. Start Shizuku service")
            .setPositiveButton("Open Shizuku") { _, _ -> openShizukuApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProgress(show: Boolean) {
        binding.progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSelectFont.isEnabled = !show
        binding.btnInstallFont.isEnabled = !show && isShizukuPermissionGranted && selectedFontUri != null
    }

    private fun showStatus(message: String, isSuccess: Boolean) {
        runOnUiThread {
            binding.statusMessage.text = message
            binding.statusMessage.setTextColor(
                ContextCompat.getColor(this, if (isSuccess) R.color.success else R.color.error)
            )
            binding.statusMessage.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove listeners
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
    }
}
