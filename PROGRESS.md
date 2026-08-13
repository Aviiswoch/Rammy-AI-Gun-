# Progress

Last updated: 2026-08-13

## Native UVC YUY2 frame-integrity patch

- Correlated the SM-A156E physical diagnostics with the exact UVCAndroid 1.0.13 native failure paths; the full-size RGBX callback could contain stale regions from a partial raw YUY2 conversion
- Preserved safe rollback at commit `43f55bd` and vendored the matching upstream UVCAndroid 1.0.13 `libuvccamera` source (commit `6018c7f20238832929cf61427427bb7828056260`) into `android/uvcandroid`
- Replaced only the Maven binary dependency with the source-built project module; existing CameraHelper/USB/permission/preview/photo/recording APIs remain unchanged
- Added frame-level corruption and EOF state in native libuvc
- UVC ERR packets, failed isochronous packets/transfers, missing-EOF FID changes, empty EOF frames, overflow, and YUY2 size mismatches are discarded before decode/render
- Removed the unsafe publish-on-maximum-size shortcut; explicit EOF is required
- Added exact `width * height * 2` YUY2 validation before conversion and preview enqueue
- Partial YUY2 input can no longer partially overwrite and publish a reused RGBX destination; the renderer keeps its last good frame
- Added nine native packet/frame integrity counters through JNI and the existing debug Diagnostics/Copy/Export flow
- Added periodic `[UVC_NATIVE]` summaries and debug-native `[UVC_DROP]` reasons; the green-block heuristic remains debug-only and is not used for production acceptance
- Debug unit tests: **passed** (8 tests, 0 failures/errors)
- Native debug compilation for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`: **passed**
- Debug APK: **passed**, signed/aligned, package `com.rammy.aigun.debug`, version `1.0.0-debug` (`versionCode` 2)
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Debug APK modified: `2026-08-13 17:09:04 +05:30`; size: 28,632,077 bytes
- Debug APK SHA-256: `77D0436D0BA6978C174897B68EC0A157C983105D02738B45C9B8F240913DB357`
- Release native compilation for all four ABIs and release Kotlin compilation: **passed**
- No ADB device was connected. Source-level fix implemented; physical UVC verification required on the affected phone for at least five minutes
- Integration details: `UVC_NATIVE_PATCH.md`; fix and test instructions: `UVC_ARTIFACT_FIX.md`

## UVC artifact diagnostic inspection

- Diagnosed the full UVCAndroid 1.0.13 USB packet -> native frame assembly -> MJPEG/YUYV decode -> shared OpenGL renderer -> preview/photo/recording path
- Found native incomplete-frame acceptance paths (ERR/bad isochronous packet does not invalidate the frame; missing-EOF FID change publishes accumulated bytes; downstream size guard is commented out)
- Found a conditional stale-buffer mechanism in partial YUYV conversion and a secondary ANativeWindow stride/geometry risk
- Added debug-only negotiated-mode, endpoint, device, decoded-frame, rendered-frame, size-mismatch, buffer-object, transform, recording, and sampled color-block diagnostics
- Added `[CORRUPT_FRAME]` logging for decoded RGBX size mismatches and sudden green/magenta block heuristics; no frame is filtered or discarded
- Existing diagnostics can be copied; debug builds can export `Downloads/Rammy AI Gun/uvc-diagnostics.txt`
- Raw UVC packet/FID/EOF/ERR, compressed MJPEG, decoder-failure, and native USB transfer counters are explicitly reported as unavailable through the binary AAR API
- Debug unit tests, debug APK build, and release Kotlin compilation: **passed**
- No physical device was connected; Android 15/16 correlation is pending
- No USB connection, permission, mode selection, preview, media, transform, UI branding, version, or release behavior was changed
- Full findings and two-device matrix: `UVC_ARTIFACT_INSPECTION.md`

## Installable release APK fix

- Root cause confirmed: the previous `app-release-unsigned.apk` contained no signing certificate; `apksigner` reported `DOES NOT VERIFY` and `Missing META-INF/MANIFEST.MF`
- Added release signing support through external `RAMMY_RELEASE_*` Gradle properties/environment variables; no keystore or secrets committed
- No official Rammy AI Gun release/upload keystore was found locally
- Created a sideload-only release using the existing Android debug signing identity through the explicit `rammyLocalTestSigning=true` build switch
- Final standalone APK: `release-output/Rammy-AI-Gun-1.0.0.apk`
- Gradle release output: `android/app/build/outputs/apk/release/app-release.apk`
- Final package: `com.rammy.aigun`
- Final version: `1.0.0` (`versionCode` 2)
- Final APK size: 10,182,122 bytes
- Final APK SHA-256: `25B63E26D82BD4C911097B48915254E21FD676CC0CC2689754BC4D758D345C90`
- Signature verification: **passed** (one signer, APK Signature Scheme v2)
- Zip alignment verification: **passed**
- Package/badging verification: **passed**
- Universal ABI verification: **passed** (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` with UVC native libraries)
- Clean `assembleRelease`: **passed**
- Release unit tests: **passed**
- Physical installation and camera regression checks: **not run**; no ADB device was connected
- Camera, USB/UVC, capture, recording, permissions, UI, and logo source files were not changed for this fix
- Full diagnosis and signing-identity limitations: `RELEASE_APK_FIX.md`

## Play Store identity update

- Official launcher logo changed to the supplied `logo.png` without redesign, recoloring, cropping, or proportion changes
- Exact source preserved at `shared-assets/rammy-ai-gun-logo.png`
- Source PNG: 1254 x 1254 RGB; SHA-256 `D9D8E624E9897E9023D7AE42CB44876DBB3FAF0A21383E77593CD2DB9E076250`
- Normal and round launcher PNGs generated for mdpi (48), hdpi (72), xhdpi (96), xxhdpi (144), and xxxhdpi (192)
- Adaptive launcher and adaptive round launcher configured for Android 8+ with a mask-safe inset and black background
- Manifest `android:icon` and `android:roundIcon` now reference the new launcher resources
- Final `versionName`: `1.0.0`
- Final `versionCode`: `2` (advanced conservatively because Play Console usage of code 1 cannot be verified from the repository)
- Original release APK build: **passed but unsigned** (`app-release-unsigned.apk`); superseded by the signed local-test artifact documented above
- Release AAB build: **passed** (`app-release.aab`)
- The AAB remains unsuitable for Play upload until rebuilt with the official Rammy upload/release key
- Release lint-vital check: **passed**
- Camera, USB/UVC, media, permission, and UI-control source files were not changed for this identity update

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
- Release signing and Play Console upload
