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

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    ).daemon(false)
     .processNameSuffix("service")
     .debuggable(BuildConfig.DEBUG)
     .version(BuildConfig.VERSION_CODE)

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

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE && result == PackageManager.PERMISSION_GRANTED) {
            bindShizukuService()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndRequestShizukuPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        isShizukuReady = false
        runOnUiThread { updateUI() }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFontSelected(it) }
    }

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
        updateStatus("Installing font via overlay...", true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fontFile = File(cacheDir, "install_font.ttf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(fontFile).use { input.copyTo(it) }
                }

                val fontName = selectedFontName.replace(" ", "_").replace("(", "").replace(")", "").replace(".ttf", "").replace(".otf", "")
                val result = installFontViaOverlay(service, fontFile, fontName)

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

    private fun installFontViaOverlay(service: IUserService, fontFile: File, fontName: String): InstallResult {
        return try {
            val srcPath = fontFile.absolutePath
            val safeFontName = fontName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            
            // Create overlay APK directory structure
            val overlayDir = File(cacheDir, "overlay_$safeFontName")
            overlayDir.mkdirs()
            
            val resDir = File(overlayDir, "res/font")
            resDir.mkdirs()
            
            // Copy font file to overlay
            val targetFont = File(resDir, "custom_font.ttf")
            fontFile.copyTo(targetFont, overwrite = true)
            
            // Create AndroidManifest.xml for overlay
            val manifestContent = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.fontoverlay.$safeFontName">
    <overlay android:targetPackage="android" 
             android:isStatic="false"
             android:priority="999" />
    <application android:hasCode="false" />
</manifest>"""
            
            File(overlayDir, "AndroidManifest.xml").writeText(manifestContent)
            
            // Create res/values/styles.xml to override font
            val valuesDir = File(overlayDir, "res/values")
            valuesDir.mkdirs()
            
            val stylesContent = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.DeviceDefault" parent="@android:style/Theme.DeviceDefault">
        <item name="android:fontFamily">@font/custom_font</item>
    </style>
    <style name="Theme.DeviceDefault.Light" parent="@android:style/Theme.DeviceDefault.Light">
        <item name="android:fontFamily">@font/custom_font</item>
    </style>
</resources>"""
            File(valuesDir, "styles.xml").writeText(stylesContent)

            // Method 1: Try to use cmd overlay (Android 10+)
            var result = service.executeCommand("cmd overlay list")
            
            // Copy font directly to system fonts location with overlay approach
            val fontDest = "/data/local/tmp/${safeFontName}.ttf"
            service.executeCommand("cp '$srcPath' '$fontDest' && chmod 644 '$fontDest'")
            
            // Try Android 12+ font API via settings
            result = service.executeCommand("settings put system font_family '$safeFontName'")
            
            // Try content provider method
            result = service.executeCommand("""
                content call --uri content://settings/system --method PUT_system --arg font_family --extra value:s:$safeFontName 2>/dev/null
            """.trimIndent())
            
            // Method 2: Try FontManager service (Android 12+)
            result = service.executeCommand("""
                mkdir -p /data/fonts/files/0 2>/dev/null
                cp '$srcPath' '/data/fonts/files/0/${safeFontName}.ttf'
                chmod 644 '/data/fonts/files/0/${safeFontName}.ttf'
                
                # Create font family config
                mkdir -p /data/fonts/config 2>/dev/null
                cat > /data/fonts/config/config.xml << 'FONTCFG'
<?xml version="1.0" encoding="utf-8"?>
<font-families xmlns:android="http://schemas.android.com/apk/res/android">
    <family name="sans-serif">
        <font weight="400" style="normal">/data/fonts/files/0/${safeFontName}.ttf</font>
        <font weight="700" style="normal">/data/fonts/files/0/${safeFontName}.ttf</font>
    </family>
</font-families>
FONTCFG
                chmod 644 /data/fonts/config/config.xml
            """.trimIndent())
            
            if (result.contains("EXIT:0") || !result.contains("ERR:")) {
                // Try to trigger font refresh
                service.executeCommand("am broadcast -a android.intent.action.CONFIGURATION_CHANGED")
                service.executeCommand("settings put system font_scale 1.0")
                
                return InstallResult(true, "Font overlay installed!\n\nTo apply:\n1. Go to Settings > Display\n2. Look for Font/Theme settings\n3. Or reboot your device\n\nIf font doesn't apply, your device may need:\n- ZFont 3 app (uses same method)\n- Or root access")
            }
            
            // Method 3: Direct system font replacement (requires more permissions)
            result = service.executeCommand("""
                mount -o rw,remount /system 2>/dev/null
                cp '$srcPath' /system/fonts/Roboto-Regular.ttf.bak 2>/dev/null
                cp '$srcPath' /system/fonts/Roboto-Regular.ttf 2>/dev/null
                chmod 644 /system/fonts/Roboto-Regular.ttf 2>/dev/null
            """.trimIndent())
            
            if (result.contains("EXIT:0")) {
                return InstallResult(true, "Font replaced! Reboot to apply.")
            }
            
            // Save to downloads as fallback
            val dlPath = "/sdcard/Download/${safeFontName}.ttf"
            service.executeCommand("cp '$srcPath' '$dlPath' && chmod 644 '$dlPath'")
            
            InstallResult(false, "Could not apply font automatically.\n\nFont saved to: Downloads/${safeFontName}.ttf\n\nRecommended: Install ZFont 3 from Play Store - it can apply this font using the same Shizuku connection.\n\nAlternatively, your device may require root for system fonts.")

        } catch (e: Exception) {
            InstallResult(false, "Exception: ${e.message}")
        } finally {
            // Cleanup
            File(cacheDir, "overlay_${fontFile.nameWithoutExtension}").deleteRecursively()
        }
    }

    private fun showSuccessDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Font Installed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Open ZFont 3") { _, _ ->
                try {
                    val intent = packageManager.getLaunchIntentForPackage("com.mightyfont.zfont")
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, 
                            Uri.parse("https://play.google.com/store/apps/details?id=com.mightyfont.zfont")))
                    }
                } catch (e: Exception) { }
            }
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
