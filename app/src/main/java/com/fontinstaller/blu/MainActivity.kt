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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fontinstaller.blu.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFontUri: Uri? = null
    private var selectedFontName: String = ""
    private var isShizukuAvailable = false
    private var isShizukuPermissionGranted = false

    private val SHIZUKU_REQUEST_CODE = 100

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            updateShizukuStatus()
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        isShizukuAvailable = true
        checkShizukuPermission()
        updateShizukuStatus()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        isShizukuAvailable = false
        isShizukuPermissionGranted = false
        updateShizukuStatus()
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSelectedFont(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) openFilePicker()
    }

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) openFilePicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupShizuku()
        setupClickListeners()
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        try {
            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true
                checkShizukuPermission()
            }
        } catch (e: Exception) { }
        updateShizukuStatus()
    }

    private fun checkShizukuPermission() {
        if (!isShizukuAvailable) return
        try {
            isShizukuPermissionGranted = !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { }
    }

    private fun updateShizukuStatus() {
        runOnUiThread {
            if (isShizukuAvailable && isShizukuPermissionGranted) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
                binding.shizukuStatusText.text = getString(R.string.shizuku_connected)
                binding.btnInstallFont.isEnabled = selectedFontUri != null
            } else if (isShizukuAvailable) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
                binding.shizukuStatusText.text = "Shizuku Running - Tap to Grant Permission"
                binding.btnInstallFont.isEnabled = false
            } else {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
                binding.shizukuStatusText.text = getString(R.string.shizuku_disconnected)
                binding.btnInstallFont.isEnabled = false
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectFont.setOnClickListener { checkStoragePermissionAndOpenPicker() }
        binding.btnInstallFont.setOnClickListener {
            if (!isShizukuPermissionGranted && isShizukuAvailable) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            } else {
                selectedFontUri?.let { installFont(it) }
            }
        }
        binding.btnOpenShizuku.setOnClickListener { openShizukuApp() }
    }

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) startActivity(intent)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
        } catch (e: Exception) { }
    }

    private fun checkStoragePermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) openFilePicker()
            else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
        } else {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) openFilePicker()
            else storagePermissionLauncher.launch(perms)
        }
    }

    private fun openFilePicker() { filePickerLauncher.launch("*/*") }

    private fun handleSelectedFont(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.lowercase().endsWith(".ttf") && !fileName.lowercase().endsWith(".otf")) {
            showStatus("Please select a TTF or OTF file", false)
            return
        }
        selectedFontUri = uri
        selectedFontName = fileName
        binding.selectedFontName.text = fileName
        binding.selectedFontPath.text = uri.path ?: ""
        loadFontPreview(uri)
        binding.btnInstallFont.isEnabled = isShizukuAvailable && isShizukuPermissionGranted
        showStatus("Font selected: $fileName", true)
    }

    private fun getFileName(uri: Uri): String {
        var name = "font.ttf"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    private fun loadFontPreview(uri: Uri) {
        try {
            val tempFile = File(cacheDir, "temp_font.ttf")
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { input.copyTo(it) } }
            binding.fontPreviewText.typeface = Typeface.createFromFile(tempFile)
            binding.previewCard.visibility = View.VISIBLE
            tempFile.delete()
        } catch (e: Exception) { binding.previewCard.visibility = View.GONE }
    }

    private fun installFont(uri: Uri) {
        showProgress(true)
        showStatus("Preparing font...", true)
        Thread {
            try {
                val fontFile = File(cacheDir, "install_font.ttf")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(fontFile).use { input.copyTo(it) } }
                
                // Copy font to Downloads folder
                val fontName = selectedFontName.replace(" ", "_")
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(dlDir, fontName)
                fontFile.copyTo(destFile, overwrite = true)
                
                fontFile.delete()
                
                runOnUiThread {
                    showProgress(false)
                    showSuccessDialog("Font saved to:\nDownloads/$fontName\n\nTo apply system-wide:\n1. Open MT Manager\n2. Grant Shizuku permission\n3. Copy font to /system/fonts/\n4. Reboot device")
                }
            } catch (e: Exception) {
                runOnUiThread { showProgress(false); showStatus("Error: ${e.message}", false) }
            }
        }.start()
    }

    private fun showSuccessDialog(msg: String) {
        AlertDialog.Builder(this).setTitle("Font Ready").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun showProgress(show: Boolean) {
        binding.progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSelectFont.isEnabled = !show
        binding.btnInstallFont.isEnabled = !show && selectedFontUri != null && isShizukuPermissionGranted
    }

    private fun showStatus(msg: String, success: Boolean) {
        runOnUiThread {
            binding.statusMessage.text = msg
            binding.statusMessage.setTextColor(ContextCompat.getColor(this, if (success) R.color.success else R.color.error))
            binding.statusMessage.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
    }
}
