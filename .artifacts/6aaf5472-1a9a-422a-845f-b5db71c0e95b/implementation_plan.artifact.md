# Implementation Plan - Complete Rewrite and Robust Region OCR

This plan addresses the issue where OCR results leak outside the selected region over time and implements full persistence for both apps.

## User Review Required

> [!IMPORTANT]
> The "Complete Rewrite" will focus on refactoring the core logic for robustness while maintaining the current feature set. Existing Firebase connections will remain compatible.

## Proposed Changes

### [Main App] - OCR Region Robustness & Persistence

#### [MODIFY] [ScreenCaptureService.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/app/src/main/java/com/screenocr/monitor/service/ScreenCaptureService.kt)
- **Dynamic Display Handling**: Implement a `DisplayListener` to detect screen rotation or resolution changes. Re-initialize `VirtualDisplay` and `ImageReader` on changes to prevent coordinate drift.
- **Strict Region Cropping**: Refine `captureScreenRegion()` to ensure the crop is always relative to the current image buffer's actual dimensions.
- **State Management**: Clearer lifecycle for `MediaProjection` and `ImageReader`.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/app/src/main/java/com/screenocr/monitor/MainActivity.kt)
- **State Restoration**: Ensure all UI fields (presets, modes, intervals) are loaded in `onCreate` and saved on every change.
- **Service Interaction**: Improve how the app communicates with the running service to ensure they always use the same `AppConfig`.

#### [MODIFY] [OverlayService.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/app/src/main/java/com/screenocr/monitor/service/OverlayService.kt)
- **Precise Bounds**: Ensure the overlay box coordinates are perfectly mapped to the screen pixels.
- **Auto-Save**: Ensure region changes are immediately persisted and pushed to the service.

#### [MODIFY] [PreferencesManager.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/app/src/main/java/com/screenocr/monitor/utils/PreferencesManager.kt)
- Add methods to save/load individual fields or the entire `AppConfig` more efficiently.

### [Companion App] - Full Persistence

#### [MODIFY] [MainActivity.kt](file:///C:/Users/imran/screen_ocr_monitor/android_app/companion/src/main/java/com/screenocr/companion/MainActivity.kt)
- **Auto-Save/Load**: Save the `target_uid`, `alert_sound_uri`, and even the last synced `AppConfig` to `SharedPreferences`.
- **UI Recovery**: On launch, restore the connection state if it was previously active.

## Verification Plan

### Automated Tests
- Build verification for both modules.

### Manual Verification
1. **Region Accuracy**: Select a region, verify OCR results. Rotate screen, verify results stay within the box.
2. **Persistence (Main)**: Change settings, kill app, restart. Verify settings are restored.
3. **Persistence (Companion)**: Enter UID, select sound, kill app, restart. Verify UID and sound are restored.
4. **Background Monitoring**: Verify background OCR and alarm still work as expected.
