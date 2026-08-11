# Progress

Last updated: 2026-08-11

## Milestone status

- Milestone 1: complete (Android build verified; iOS requires macOS/Xcode verification)
- Milestone 2: complete in code; physical hot-plug validation pending
- Milestone 3: technically implemented and ready for physical-device testing; physical-device gate not passed
- Milestones 4–11: not started

## Implemented

- Repository architecture and platform compatibility policy
- Native Android/iOS project scaffolding
- Android Compose monitor shell and startup states
- Android USB host capability detection, UVC classification, attach/detach lifecycle, official USB permission handoff, descriptor-backed native UVC open, hardware preview surface, fallback requests, and timing hooks
- Apple AVFoundation/SwiftUI capability shell

## Verification

- Android `assembleDebug`: **passed** (43 tasks, 2026-08-11)
- Android `testDebugUnitTest`: **passed**
- Android lint: attempted; blocked before analysis by TLS failures downloading the Android lint engine from Google Maven. No lint result is claimed.
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Physical UVC preview: **not tested**
- iOS build and hardware capability: **not tested** (Xcode is unavailable in this Windows workspace)

## Deliberately not implemented yet

Photo capture, video recording, gallery, polished settings, transforms, diagnostics screen, and release packaging. These remain behind the real-camera preview validation gate.
