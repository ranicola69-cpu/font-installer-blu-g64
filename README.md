# Font Installer for Blu G64

A native Android app that installs system fonts using Shizuku on the Blu G64 (and other Android devices).

## Features

- 📁 Pick TTF/OTF font files from device storage
- 👀 Preview fonts before installing
- 🔌 Shizuku integration for privileged access (no root required)
- 🎨 Modern Material Design 3 UI
- 📱 Specifically designed for Blu G64

## Prerequisites

Before using this app, you need:

1. **Shizuku App** - Download from [Google Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
2. **Developer Options enabled** on your Blu G64
3. **Wireless Debugging enabled** (Android 11+)

## Setting Up Shizuku

1. Install Shizuku from Play Store
2. Enable Developer Options:
   - Go to Settings > About Phone
   - Tap "Build number" 7 times
3. Enable Wireless Debugging:
   - Go to Settings > Developer Options
   - Enable "Wireless debugging"
4. Open Shizuku and tap "Start via Wireless debugging"
5. Follow the pairing instructions

## Building the APK

### Option 1: Using Android Studio (Recommended)

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to the `android-font-installer` folder
4. Wait for Gradle sync to complete
5. Click "Build" > "Build Bundle(s) / APK(s)" > "Build APK(s)"
6. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Using Command Line

```bash
# Make sure you have Android SDK installed
export ANDROID_HOME=/path/to/android-sdk

# Navigate to the project directory
cd android-font-installer

# Build the APK
./gradlew assembleDebug

# Find APK at app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: Using GitHub Actions

Create a repository with this code and use the provided workflow in `.github/workflows/build.yml` to automatically build the APK on every push.

## How It Works

1. **Select Font**: Pick a TTF or OTF file from your device storage
2. **Preview**: See how the font looks before installing
3. **Install**: The app uses Shizuku to copy the font to system directories
4. **Reboot**: Restart your device to apply the new font

## Technical Details

The app attempts multiple methods to install fonts:

1. **Android 12+ FontManager**: Copies fonts to `/data/fonts/files/` with config updates
2. **Legacy System Fonts**: Attempts to write to `/system/fonts/` (requires remount)
3. **Manual Fallback**: Copies font to Downloads for manual installation

## Troubleshooting

### "Shizuku Not Available"
- Make sure Shizuku app is installed and running
- Restart Shizuku service after device reboot

### "Permission Denied"
- Grant permission when Shizuku prompts you
- Make sure Shizuku service is properly started

### Font doesn't apply after reboot
- The font installation method may not be compatible with your Android version
- Try using MT Manager + Shizuku as an alternative method

## File Structure

```
android-font-installer/
├── app/
│   ├── src/main/
│   │   ├── java/com/fontinstaller/blu/
│   │   │   └── MainActivity.kt
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## License

MIT License - Feel free to modify and distribute.

## Support

If this app doesn't work on your Blu G64, please try:
1. MT Manager + Shizuku method
2. iFont app
3. Manual font replacement with ADB

These are alternative methods that may work better on specific device configurations.
