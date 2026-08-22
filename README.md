# Screen OCR Monitor & Alert System (Android)

A modern, high-performance native Android application for real-time on-device screen OCR monitoring, floating overlay region borders, and continuous sound/vibration alerts.

---

## Features

- **On-Device OCR (Google ML Kit)**: 100% offline, zero latency, runs directly on device hardware with high accuracy.
- **MediaProjection Screen Capture**: High-efficiency background capture service capturing only the active monitored region.
- **Floating Region Border Overlay (`SYSTEM_ALERT_WINDOW`)**:
  - Live red border box overlaying the exact coordinates on top of whatever app you are monitoring.
  - Interactive touch-drag to move and corner drag-handle to resize the monitoring box directly on screen.
  - Floating toolbar with quick **Start / Stop Monitoring**, **🔕 Silence Alarm**, and **Region Toggle** controls.
- **Mathematical & Text Condition Matching**:
  - `Higher than (>)`
  - `Lower than (<)`
  - `Equal to (=)`
  - `Higher or Equal (>=)`
  - `Lower or Equal (<=)`
  - `Contains`
  - `Exact`
  - `Regex`
  - `Fuzzy Similarity` (with custom threshold slider)
- **Continuous Beeping & Audio Alerts**:
  - Repeating alert tone with customizable beep interval (`0.1s` – `10.0s`).
  - Instant silence via floating overlay button, persistent notification action, or main UI.
- **Auto-Persistence**: All settings, intervals, presets, and region dimensions persist across app sessions.

---

## Project Structure

```
android_app/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/screenocr/monitor/
│       │   │   ├── MainActivity.kt               # Main configuration & log console UI
│       │   │   ├── model/
│       │   │   │   ├── AppConfig.kt              # App settings data model
│       │   │   │   └── MatchMode.kt              # Match mode enum (Math, Regex, Text)
│       │   │   ├── ocr/
│       │   │   │   ├── MLKitOcrEngine.kt         # On-device Google ML Kit Text Recognition
│       │   │   │   └── MatchEvaluator.kt         # String & mathematical evaluator engine
│       │   │   ├── service/
│       │   │   │   ├── AlarmPlayer.kt            # ToneGenerator & haptic vibrator engine
│       │   │   │   ├── OverlayService.kt         # Floating border & controls window manager
│       │   │   │   └── ScreenCaptureService.kt   # Foreground MediaProjection capture service
│       │   │   └── utils/
│       │   │       └── PreferencesManager.kt     # SharedPreferences persistence
│       │   └── res/                              # Layouts, colors, drawables, themes
│       └── test/
│           └── java/com/screenocr/monitor/
│               └── MatchEvaluatorTest.kt         # Mathematical & string evaluation unit tests
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## How to Build & Run

### Method 1: In Android Studio
1. Open **Android Studio**.
2. Select **Open** and choose the `c:\Users\imran\screen_ocr_monitor\android_app` directory.
3. Wait for Gradle Sync to complete.
4. Connect an Android device (via USB with USB Debugging enabled) or start an Android Emulator (API 26+).
5. Click **Run (`Shift + F10`)** to build and install the APK.

### Method 2: Command Line Build (Gradle)
```bash
cd android_app
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

Install to a connected device via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions Required on Android

1. **Display over other apps**: Prompted on first launch to allow the red region border and floating action bar over other apps.
2. **Screen Recording / Capture**: Prompted by Android system dialog (`MediaProjection`) when tapping **Start Monitoring**.
3. **Notifications**: For background service status and one-tap alarm silencing.
