# Progress

Last updated: 2026-08-12

## Milestone status

- Milestone 1: complete (Android build verified; iOS requires macOS/Xcode verification)
- Milestone 2: complete in code; physical hot-plug validation pending
- Milestone 3: technically implemented and ready for physical-device testing; physical-device gate not passed
- Milestones 4-11: not started

## Android USB/OTG fix implemented

- Confirmed that the app and UVCAndroid library do not launch Android USB Settings in the normal connection flow
- USB Host capability check and `USB_HOST_SUPPORTED` diagnostic
- Native `UsbManager.deviceList` enumeration on startup, resume, attach, and manual retry
- Device-level and interface-level UVC classification for composite cameras
- Broad class-based attach filters without vendor/product lock-in
- App-owned attach, detach, and `com.rammy.aigun.USB_PERMISSION` broadcast handling
- In-app CAMERA runtime permission explanation and request
- App-owned `UsbManager.requestPermission()` flow with Android-version-appropriate mutable `PendingIntent`
- Automatic continuation after permission; no second Connect press
- Explicit `UsbManager.openDevice()` probe and `USB_OPEN_FAILED` stage
- Existing native UVCAndroid/libuvc preview retained
- MJPEG-first 1080p -> 720p -> 480p fallback, followed by uncompressed YUY2/YUV modes
- Explicit connection state machine that prevents duplicate permission/open attempts
- Automatic safe close on detach and automatic reconnect on the next attach
- Copyable Settings / Diagnostics / USB screen
- Debug-only descriptor, permission, opening, negotiation, first-frame, and timing logs
- Android application settings link only after CAMERA runtime permission is permanently denied

## Verification

- Android `testDebugUnitTest`: **passed** (2026-08-12)
- Android `assembleDebug`: **passed** (2026-08-12)
- Merged manifest audit: **passed**
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Physical UVC preview: **not tested**
- Physical procedure: `docs/ANDROID_USB_PHYSICAL_TEST.md`
- iOS build and hardware capability: **not tested** (Xcode is unavailable in this Windows workspace)

## Deliberately not implemented in this task

Photo capture, video recording, gallery, visual redesign, ads, iOS changes, and unrelated settings remain behind the real-camera preview validation gate.
