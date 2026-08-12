# Progress

Last updated: 2026-08-12

## Milestone status

- Milestone 1: complete
- Milestone 2: complete; Android physical hot-plug verified by the user
- Milestone 3: complete; Android external UVC live preview verified by the user
- Milestone 4: camera rotation, Fit/Fill, fullscreen, and reconnect-safe UI controls implemented; physical control validation pending
- Milestone 5: Android/iOS photo capture and system Gallery/Photos publication implemented; physical validation pending
- Milestone 6: Android/iOS video recording and system Gallery/Photos publication implemented; physical validation pending
- Milestone 7: internal Gallery intentionally not started
- Milestone 8: visible camera Settings/Info controls wired; full settings milestone not started
- Milestones 9-10: Apple capability path and controls implemented in code; Xcode/hardware validation pending
- Milestone 11: not started

## Working Android USB/UVC core preserved

- No change to USB permission behavior
- No change to OTG attach/detach discovery
- No change to `UsbManager.openDevice()` verification
- No replacement of UVCAndroid/libuvc
- No change to negotiated-mode selection or fallback order
- Existing `CameraHelper` and `FirstFrameTextureView` remain the camera and preview owners

## Camera controls implemented

- Photo from the UVC renderer, JPEG quality 95
- Photos published to `Pictures/Rammy AI Gun` through MediaStore
- Hardware/surface H.264 MP4 recording through the existing UVC library
- Videos published to `Movies/Rammy AI Gun` through MediaStore
- Recording timer, active red/stop state, duplicate-action protection, and interruption cleanup
- 0/90/180/270 renderer rotation
- Aspect-correct Fit/Fill
- Fullscreen and hide/show overlays with single-tap restoration
- Live camera Information overlay
- Both Settings buttons wired to the existing diagnostics/settings overlay
- Press/haptic/shutter feedback and short state confirmations
- Apple AVFoundation photo/video/transform equivalents where external cameras are exposed
- Gallery button intentionally left visible but disabled

Full implementation and physical test details: `CAMERA_CONTROLS.md`.

## Verification

- Android `compileDebugKotlin`: **passed** (2026-08-12)
- Android `testDebugUnitTest`: **passed** (2026-08-12)
- Android `assembleDebug`: **passed** (2026-08-12)
- Android merged-manifest audit: only legacy `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`; no modern broad storage/read-media permission
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Working physical Android UVC preview: **confirmed by user before this controls task**
- Physical photo/video/control validation: **pending**
- iOS build and hardware validation: **pending** (Xcode unavailable in this Windows workspace)

## Intentionally unfinished

- Internal Gallery screen
- Visible mirror/vertical-flip buttons (none exist in the supplied screen)
- Recording audio
- Full settings milestone beyond the already-visible diagnostics/settings overlay
- Release packaging
