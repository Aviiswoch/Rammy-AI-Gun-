# UVC YUY2 Artifact Source-Level Fix

Date: 2026-08-13  
Status: source-level fix implemented; physical UVC verification required

## Confirmed input

Affected physical session:

- Samsung SM-A156E, Android 16 / SDK 36, hardware `mt6835`
- camera VID 13468 / PID 1041
- 640x480 YUY2 at 30 FPS
- isochronous IN endpoint, maximum packet size 5120, interval 1
- expected raw YUY2: 614400 bytes
- expected/observed decoded RGBX: 1228800 bytes
- 115 suspicious green decoded frames in 942 decoded frames
- zero application-level RGBX-size mismatches

The full-size RGBX result did not prove the raw YUY2 source was complete. The former converter could partially overwrite a reused full-size RGBX allocation and return success.

## Exact source patched

The Maven binary `com.herohan:UVCAndroid:1.0.13` was replaced with a source-built project dependency from the matching upstream tag/commit. See `UVC_NATIVE_PATCH.md`.

Native files changed:

- `libuvc/src/stream.c`
- `libuvc/include/libuvc/libuvc_internal.h`
- `libuvc/include/libuvc/libuvc.h`
- `libuvc/src/frame.c`
- `UVCCamera/UVCPreview.cpp`
- `UVCCamera/registerUVCCamera.cpp`
- `com/serenegiant/usb/UVCCamera.java`

App diagnostics changed:

- `UvcArtifactDiagnostics.kt`
- `UsbCameraController.kt`

## Old behavior

1. A UVC ERR payload or failed isochronous packet was skipped without invalidating the frame.
2. A FID transition with accumulated bytes called `_uvc_swap_buffers()` even when EOF was missing.
3. Reaching `dwMaxVideoFrameSize` could publish without EOF.
4. The downstream incomplete-frame guard was commented out.
5. `uvc_yuyv2rgbx()` converted only the available bytes, returned success, and left the rest of a reused RGBX buffer stale.
6. The full destination was posted to the renderer, photo, and recording branches.

This directly explains sharply bounded green/broken rectangles despite a full-size application RGBX callback.

## New native acceptance rule

An assembling frame now tracks corruption and EOF state. A frame crosses into decode/render only when:

```text
valid UVC payload headers
+ no UVC ERR or failed packet/transfer during assembly
+ explicit EOF observed
+ for YUY2: assembled bytes exactly equal dwMaxVideoFrameSize
```

Specific behavior:

- UVC ERR and failed isochronous packets mark the current frame corrupt.
- A FID change with accumulated data but no EOF discards and resets the previous frame; it never swaps/publishes it.
- The “buffer reached maximum size” shortcut no longer publishes without EOF.
- Oversized assembly is marked corrupt and bounded safely.
- EOF completes the decision: corrupt, empty, and size-mismatched frames are reset without publication.
- YUY2 frames are also checked against `width * height * 2` immediately before conversion and again at the preview callback boundary.
- `uvc_yuyv2rgbx()` returns failure before touching the reusable RGBX output when source length is not exact.

## Last-good-frame behavior

The existing implementation already uses assembly/hold buffers plus a reusable preview frame pool. It draws/enqueues only when conversion returns success. The patch preserves that architecture: an invalid back/raw frame is never swapped to consumers, and a failed conversion is recycled without drawing. The renderer therefore continues displaying its last successfully posted texture until the next valid frame.

There is no black placeholder, camera restart, USB reconnect, mode fallback, per-frame Bitmap allocation, or green-pixel production filter.

## Photo and recording path

`CameraInternal` feeds one validated primary renderer texture. Preview, `ImageCapture`, and surface-input `VideoCapture` branch from that renderer. Dropping an invalid frame before the native primary surface means those branches keep the last good texture and cannot receive the rejected raw frame. No photo/recording API or media-storage behavior changed.

## Native counters

Counters reset at native stream start and are available through `UVCCamera.getIntegrityStats()`:

- `uvcPacketsTotal`
- `uvcPacketsErr`
- `framesCompletedWithEOF`
- `framesDroppedErr`
- `framesDroppedMissingEOF`
- `framesDroppedFIDTransition`
- `framesDroppedSizeMismatch`
- `rawYuy2FramesAccepted`
- `rawYuy2FramesDropped`

The existing debug Diagnostics screen and copied/exported report include all counters. Logcat emits periodic `[UVC_NATIVE]` summaries and native rejected-frame reasons use `[UVC_DROP]`. The sampled green-block heuristic remains debug-only and is not part of frame acceptance.

## Build verification

- Source-built native debug module, all ABIs: **passed**
- Debug unit tests: **passed** (8 tests, 0 failures/errors)
- Debug app APK build: **passed**
- Release native module, all ABIs: **passed**
- Release Kotlin compilation: **passed**
- APK package: `com.rammy.aigun.debug`
- APK version: `1.0.0-debug` (`versionCode` 2)
- APK signature: **valid Android debug signature, v2, one signer**
- APK zip alignment: **passed**
- Native ABI contents: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Packaged native binaries contain the JNI counter method, preview validation guard, and debug `[UVC_DROP]` reason strings for every ABI
- Final APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Modified: `2026-08-13 17:09:04 +05:30`
- Size: `28,632,077` bytes
- SHA-256: `77D0436D0BA6978C174897B68EC0A157C983105D02738B45C9B8F240913DB357`
- Connected ADB devices: none; installation and live UVC behavior were not tested from this workspace

## Required physical test

Install the newly built debug APK on the affected SM-A156E and use the same camera/cable for at least five minutes at 640x480 YUY2 30 FPS. Record:

- visible green/broken flashes
- suspicious green decoded frames
- raw YUY2 accepted/dropped
- ERR, missing-EOF, FID-transition, and size-mismatch drops
- preview smoothness
- one photo and one video, checked for corrupted frames

Expected outcome: invalid native frames are counted as dropped while suspicious decoded green frames and visible rectangular flashes fall to zero or effectively zero. This has not yet been physically verified.
