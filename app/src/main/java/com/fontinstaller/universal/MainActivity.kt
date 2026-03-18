package com.fontinstaller.universal

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
import androidx.lifecycle.lifecycleScope
import com.fontinstaller.universal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFontUri: Uri? = null
    private var selectedFontName: String = ""
    private var userService: IUserService? = null
    private var isShizukuReady = false

    companion object {
        private const val SHIZUKU_CODE = 1001
    }

    // Shizuku UserService arguments
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    ).daemon(false)
     .processNameSuffix("service")
     .debuggable(BuildConfig.DEBUG)
     .version(BuildConfig.VERSION_CODE)

    // UserService connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                userService = IUserService.Stub.asInterface(service)
                runOnUiThread {
                    updateStatus("Shizuku service connected!", true)
                    updateUI()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            runOnUiThread { updateUI() }
        }
    }

    // Shizuku permission listener
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE && result == PackageManager.PERMISSION_GRANTED) {
            bindShizukuService()
        }
    }

    // Shizuku binder listeners
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndRequestShizukuPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        isShizukuReady = false
        runOnUiThread { updateUI() }
    }

    // File picker
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFontSelected(it) }
    }

    // Storage permission
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) filePicker.launch("*/*")
    }

    private val manageStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            filePicker.launch("*/*")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShizuku()
        setupButtons()
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Shizuku.pingBinder()) {
            checkAndRequestShizukuPermission()
        }
        updateUI()
    }

    private fun checkAndRequestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                isShizukuReady = true
                bindShizukuService()
            } else {
                Shizuku.requestPermission(SHIZUKU_CODE)
            }
        } catch (e: Exception) {
            updateStatus("Shizuku error: ${e.message}", false)
        }
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            updateStatus("Failed to bind: ${e.message}", false)
        }
    }

    private fun setupButtons() {
        binding.btnSelectFont.setOnClickListener { selectFont() }
        binding.btnInstall.setOnClickListener { installFont() }
        binding.btnOpenShizuku.setOnClickListener { openShizuku() }
    }

    private fun selectFont() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                filePicker.launch("*/*")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorage.launch(intent)
            }
        } else {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                filePicker.launch("*/*")
            } else {
                storagePermission.launch(perms)
            }
        }
    }

    private fun handleFontSelected(uri: Uri) {
        val name = getFileName(uri)
        if (!name.lowercase().endsWith(".ttf") && !name.lowercase().endsWith(".otf")) {
            updateStatus("Please select a TTF or OTF font file", false)
            return
        }

        selectedFontUri = uri
        selectedFontName = name
        binding.fontName.text = name

        // Load preview
        try {
            val tempFile = File(cacheDir, "preview.ttf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            binding.previewText.typeface = Typeface.createFromFile(tempFile)
            binding.previewCard.visibility = View.VISIBLE
            tempFile.delete()
        } catch (e: Exception) {
            binding.previewCard.visibility = View.GONE
        }

        updateStatus("Font selected: $name", true)
        updateUI()
    }

    private fun getFileName(uri: Uri): String {
        var name = "font.ttf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun installFont() {
        val service = userService
        val uri = selectedFontUri

        if (service == null) {
            updateStatus("Shizuku service not connected", false)
            return
        }
        if (uri == null) {
            updateStatus("No font selected", false)
            return
        }

        showProgress(true)
        updateStatus("Installing font...", true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Copy font to cache
                val fontFile = File(cacheDir, "install_font.ttf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(fontFile).use { input.copyTo(it) }
                }

                val fontName = selectedFontName.replace(" ", "_").replace("(", "").replace(")", "")
                val srcPath = fontFile.absolutePath
                val result = installFontViaShizuku(service, srcPath, fontName)

                fontFile.delete()

                withContext(Dispatchers.Main) {
                    showProgress(false)
                    if (result.success) {
                        showSuccessDialog(result.message)
                    } else {
                        updateStatus("Failed: ${result.message}", false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    updateStatus("Error: ${e.message}", false)
                }
            }
        }
    }

    private data class InstallResult(val success: Boolean, val message: String)

    private fun installFontViaShizuku(service: IUserService, srcPath: String, fontName: String): InstallResult {
        return try {
            // Get UID to verify we have privileges
            val uid = service.getUid()
            if (uid != 2000 && uid != 0) {
                // Not running as shell or root, but still might work
            }

            // Try multiple installation methods

            // Method 1: /data/fonts (Android 12+)
            var result = service.executeCommand("mkdir -p /data/fonts/files/0 2>/dev/null")
            result = service.executeCommand("cp '$srcPath' '/data/fonts/files/0/$fontName' && chmod 644 '/data/fonts/files/0/$fontName'")
            if (result.contains("EXIT:0")) {
                // Try to set as default font by creating config
                service.executeCommand("""
                    mkdir -p /data/fonts/config 2>/dev/null
                    echo '<?xml version="1.0" encoding="utf-8"?>
                    <fontConfig>
                        <family name="sans-serif">
                            <font weight="400" style="normal">/data/fonts/files/0/$fontName</font>
                        </family>
                    </fontConfig>' > /data/fonts/config/fonts.xml
                    chmod 644 /data/fonts/config/fonts.xml
                """.trimIndent())
                return InstallResult(true, "Font installed to /data/fonts. Reboot to apply!")
            }

            // Method 2: Try /system/fonts with remount
            result = service.executeCommand("mount -o rw,remount /system 2>/dev/null; cp '$srcPath' '/system/fonts/$fontName' && chmod 644 '/system/fonts/$fontName'")
            if (result.contains("EXIT:0")) {
                return InstallResult(true, "Font installed to /system/fonts. Reboot to apply!")
            }

            // Method 3: /product/fonts
            result = service.executeCommand("mount -o rw,remount /product 2>/dev/null; cp '$srcPath' '/product/fonts/$fontName' 2>/dev/null && chmod 644 '/product/fonts/$fontName'")
            if (result.contains("EXIT:0")) {
                return InstallResult(true, "Font installed to /product/fonts. Reboot to apply!")
            }

            // Method 4: Via settings command (some devices)
            result = service.executeCommand("settings put system font_scale 1.0")

            // Fallback: Copy to accessible location
            val downloadPath = "/sdcard/Download/$fontName"
            service.executeCommand("cp '$srcPath' '$downloadPath' && chmod 644 '$downloadPath'")
            
            InstallResult(false, "Could not install to system. Font saved to Downloads.\nYour device may need root access for system font changes.")

        } catch (e: Exception) {
            InstallResult(false, "Exception: ${e.message}")
        }
    }

    private fun showSuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Success!")
            .setMessage(message)
            .setPositiveButton("Reboot Now") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        userService?.executeCommand("reboot")
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            updateStatus("Please reboot manually", false)
                        }
                    }
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openShizuku() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, 
                    Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Shizuku", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val shizukuRunning = try { Shizuku.pingBinder() } catch (e: Exception) { false }
        val serviceConnected = userService != null

        if (shizukuRunning && serviceConnected) {
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_green)
            binding.statusText.text = getString(R.string.shizuku_connected)
            binding.btnInstall.isEnabled = selectedFontUri != null
        } else if (shizukuRunning) {
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_red)
            binding.statusText.text = "Shizuku running - connecting..."
            binding.btnInstall.isEnabled = false
        } else {
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_red)
            binding.statusText.text = getString(R.string.shizuku_disconnected)
            binding.btnInstall.isEnabled = false
        }
    }

    private fun showProgress(show: Boolean) {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSelectFont.isEnabled = !show
        binding.btnInstall.isEnabled = !show && userService != null && selectedFontUri != null
    }

    private fun updateStatus(message: String, success: Boolean) {
        binding.statusMessage.text = message
        binding.statusMessage.setTextColor(
            ContextCompat.getColor(this, if (success) R.color.success else R.color.error)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) { }
    }
}
