# Universal Font Installer

A standalone Android app to install system-wide fonts using Shizuku. Works on Blu G64 and other Android devices.

## Features

- 🔤 Install TTF/OTF fonts system-wide
- 📱 Works via Shizuku (no root required)
- 👁️ Preview fonts before installing
- 🔄 Multiple installation methods for compatibility
- 🌍 Universal - works on most Android devices

## Requirements

- Android 8.0+
- Shizuku app installed and running
- Wireless Debugging enabled (for Shizuku setup)

## How to Use

1. Install Shizuku from Play Store
2. Enable Developer Options (tap Build Number 7 times)
3. Enable Wireless Debugging
4. Open Shizuku and follow pairing instructions
5. Open Universal Font Installer
6. Select your TTF font file
7. Tap Install
8. Reboot your device

## Download

Get the APK from the [Releases](../../releases) page or [Actions](../../actions) artifacts.

## Building

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`
