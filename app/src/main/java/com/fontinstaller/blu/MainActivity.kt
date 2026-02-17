package com.fontinstaller.blu

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFontUri: Uri? = null
    private var selectedFontName: String = ""
    private var isShizukuAvailable = false
    private var isShizukuPermissionGranted = false
    private var userService: IUserService? = null

    private val SHIZUKU_REQUEST_CODE = 100

    // UserService connection
    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
            runOnUiThread {
                showStatus("Shizuku service connected!", true)
                updateShizukuStatus()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            runOnUiThread {
                updateShizukuStatus()
            }
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    ).processNameSuffix("user_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    // Shizuku permission result listener
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            updateShizukuStatus()
            if (isShizukuPermissionGranted) {
                showStatus("Shizuku permission granted!", true)
                bindUserService()
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
        userService = null
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
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            handleSelectedFont(intent.data!!)
        }
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        try {
            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true
                checkShizukuPermission()
                if (isShizukuPermissionGranted) {
                    bindUserService()
                }
            }
        } catch (e: Exception) {
            isShizukuAvailable = false
        }
        updateShizukuStatus()
    }

    private fun bindUserService() {
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            showStatus("Failed to bind service: ${e.message}", false)
        }
    }

    private fun checkShizukuPermission() {
        if (!isShizukuAvailable) return

        try {
            isShizukuPermissionGranted = if (Shizuku.isPreV11()) {
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
            val serviceReady = userService != null
            if (isShizukuAvailable && isShizukuPermissionGranted && serviceReady) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
                binding.shizukuStatusText.text = getString(R.string.shizuku_connected)
                binding.btnInstallFont.isEnabled = selectedFontUri != null
            } else if (isShizukuAvailable && isShizukuPermissionGranted) {
                binding.shizukuStatusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
                binding.shizukuStatusText.text = "Shizuku Ready - Connecting..."
                binding.btnInstallFont.isEnabled = false
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
            } else if (userService == null) {
                bindUserService()
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
                val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                try {
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                }
            }
        } catch (e: Exception) {
            showStatus("Could not open Shizuku: ${e.message}", false)
        }
    }

    private fun checkStoragePermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openFilePicker()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission")
                    .setMessage("This app needs access to your files to select fonts.")
                    .setPositiveButton("OK") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

            loadFontPreview(uri)

            binding.btnInstallFont.isEnabled = userService != null

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
        }
    }

    private fun installFont(uri: Uri) {
        val service = userService
        if (service == null) {
            showStatus("Shizuku service not connected. Please try again.", false)
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

                val result = executeInstallation(service, fontFile)

                runOnUiThread {
                    showProgress(false)
                    if (result.success) {
                        showSuccessDialog(result.message)
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

    private data class InstallResult(val success: Boolean, val message: String)

    private fun executeInstallation(service: IUserService, fontFile: File): InstallResult {
        return try {
            val fontName = selectedFontName.replace(" ", "_")
            val sourcePath = fontFile.absolutePath
            
            // Method 1: Try /data/fonts (Android 12+)
            var result = service.executeCommand("mkdir -p /data/fonts/files/0")
            val targetPath = "/data/fonts/files/0/$fontName"
            
            result = service.executeCommand("cp '$sourcePath' '$targetPath' && chmod 644 '$targetPath'")
            if (!result.startsWith("ERROR") && !result.startsWith("EXCEPTION")) {
                return InstallResult(true, "Font installed to /data/fonts. Reboot required.")
            }
            
            // Method 2: Try /system/fonts with remount
            val systemPath = "/system/fonts/$fontName"
            result = service.executeCommand("mount -o rw,remount /system 2>/dev/null; cp '$sourcePath' '$systemPath' && chmod 644 '$systemPath'")
            if (!result.startsWith("ERROR") && !result.startsWith("EXCEPTION")) {
                return InstallResult(true, "Font installed to /system/fonts. Reboot required.")
            }
            
            // Method 3: Copy to Downloads as fallback
            val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/$fontName"
            fontFile.copyTo(File(downloadPath), overwrite = true)
            
            InstallResult(true, "Font copied to Downloads folder. Use MT Manager + Shizuku for manual installation.")
            
        } catch (e: Exception) {
            InstallResult(false, e.message ?: "Unknown error")
        }
    }

    private fun showSuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Font Installed")
            .setMessage(message)
            .setPositiveButton("Reboot Now") { _, _ ->
                try {
                    userService?.executeCommand("reboot")
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
            .setMessage("Shizuku service is not running. Please:\n\n1. Open Shizuku app\n2. Enable Wireless Debugging\n3. Pair using the pairing code\n4. Start Shizuku service")
            .setPositiveButton("Open Shizuku") { _, _ -> openShizukuApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProgress(show: Boolean) {
        binding.progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSelectFont.isEnabled = !show
        binding.btnInstallFont.isEnabled = !show && userService != null && selectedFontUri != null
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
