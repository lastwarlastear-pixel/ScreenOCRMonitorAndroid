# Implementation Plan - Companion App Rewrite

Complete rewrite of the companion app to provide a reliable, background-capable remote monitor and controller for the main Screen OCR Monitor app.

## Proposed Changes

### [Companion App]

#### [MODIFY] [MainActivity.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/java/com/screenocr/companion/MainActivity.kt)
- Redesign UI to include:
    - Connection management (UID persistence, Start/Stop monitoring).
    - Sound Picker for local alarm.
    - Remote Configuration panel (Preset text, Mode, Interval, Cooldown, etc.).
- Orchestrate `AlertListenerService` lifecycle.

#### [MODIFY] [activity_main.xml](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/res/layout/activity_main.xml)
- Completely rebuild layout using `ScrollView` and `CardView` for a clean, organized look.
- Sections: Connection, Local Alert Config, Remote Main App Config.

#### [MODIFY] [FirebaseSyncManager.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/java/com/screenocr/companion/firebase/FirebaseSyncManager.kt)
- Update to support targeted connection to a main device's UID.
- Methods for updating remote config and listening for alerts.

#### [MODIFY] [AlertListenerService.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/java/com/screenocr/companion/service/AlertListenerService.kt)
- Robust Foreground Service implementation.
- Real-time Firebase alert listener.
- `MediaPlayer` management for persistent alarm playback.
- `WakeLock` and `WifiLock` (if needed) for reliability when screen is off.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/AndroidManifest.xml)
- Ensure all permissions for foreground services, wake locks, and notifications are present.

## Verification Plan

### Automated Tests
- N/A (Manual UI and logic verification preferred for hardware-dependent features like background sound).

### Manual Verification
1. **Connection**: Enter main app UID, click connect. Verify "Synced" status.
2. **Remote Config**: Change "Target Text" in companion. Verify it updates in main app UI/logs.
3. **Sound Picker**: Select a custom audio file. Test playback.
4. **Detection Alarm**: Trigger a match on main app. Verify companion plays sound immediately.
5. **Background/Screen Off**: Close companion app (leave service running) and turn off screen. Trigger alert. Verify alarm plays loudly.
