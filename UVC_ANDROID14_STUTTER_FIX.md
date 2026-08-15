# Android 14 UVC Preview Stutter Fix

## Scope and verification status

This change addresses the repeated run/freeze cadence without changing USB discovery,
permissions, stream negotiation, capture, recording, or the native UVC frame-integrity
rules. No Android device was available through ADB in this workspace, so source/build
verification is complete and a five-minute physical Android 14 verification is still
required.

## Pipeline traced

The active YUY2 preview path is:

`libusb isochronous transfers`
→ `libuvc/src/stream.c payload assembly and ERR/FID/EOF/size validation`
→ `libuvc user callback thread`
→ `UVCPreview::uvc_preview_frame_callback`
→ `UVCPreview::previewFrames`
→ `UVCPreview::do_preview` YUY2-to-RGBX conversion
→ `ANativeWindow` input surface
→ `UVCCameraTextureView.RenderAgent`
→ OpenGL `SurfaceTexture.updateTexImage()` and draw
→ Android `TextureView` display.

The photo/record capture queue already used newest-frame replacement. The live preview
path did not.

## Root cause found in the source

Two backpressure points could accumulate obsolete valid work on a slower CPU/GPU:

1. `UVCPreview::previewFrames` was a four-frame FIFO. When YUY2 conversion or surface
   publication fell behind, it continued converting older valid frames. New frames were
   discarded only after the FIFO filled, which produces bursty cadence and avoidable
   latency instead of consistently showing the newest valid frame.
2. `UVCCameraTextureView.RenderAgent` posted one render message per SurfaceTexture
   callback without coalescing an already-pending draw. The SurfaceTexture itself holds
   the newest buffer, so multiple pending messages represent obsolete draw requests.

These are deterministic source-level backpressure defects and are independent of Android
version or device model. Slower scheduling/conversion/rendering makes them visible. The
new diagnostic APK records every boundary needed to confirm whether the affected Android
14 device also has upstream USB/integrity gaps. It would be inaccurate to claim the
physical device is fixed until those counters and the scene motion are observed there.

## Smallest corrective change

- The pre-conversion queue now retains only the newest already-complete, already-valid
  frame. Older valid queued frames are recycled and counted as `stale-preview` drops.
- The GL renderer now keeps at most one pending render message. A new SurfaceTexture
  notification replaces an obsolete pending request.
- No multi-second buffering was added. Queue depth is bounded to one and latency remains
  real-time.
- The debug color heuristic is sampled once per 15 callbacks (plus the first three)
  instead of running on every callback. Native integrity and frame-size checks still run
  on every packet/frame.
- Buffer reuse remains ownership-safe. Frames are recycled only after leaving the queue;
  a frame being converted or rendered is never returned by the stale-queue cleanup.

## New timing diagnostics

Debug diagnostics now report:

- raw, accepted, decoded, and rendered FPS
- average and minimum accepted FPS across one-second windows
- raw/accepted/decoded/published frame counts and timestamps
- current/maximum native conversion duration
- raw-to-decode and decode-to-render estimates
- maximum raw, decoded, and rendered gaps
- preview queue current/maximum depth
- reusable buffer-pool availability
- integrity drops and stale-valid-preview drops separately

Native counters are read once per second. Per-frame state is atomic/native or a small
fixed sample; no large per-frame Kotlin arrays, bitmaps, or buffers were introduced.

### How to locate the remaining bottleneck on the phone

- Raw FPS falls or raw max gap approaches one second: USB delivery/upstream assembly.
- Integrity-drop counters spike during a freeze: incomplete/error frames are being
  correctly rejected; inspect transfer errors rather than weakening validation.
- Accepted FPS remains steady but decoded FPS falls: conversion/preview worker pressure.
- Decoded/published FPS remains steady but rendered FPS falls: GL/UI scheduling.
- `Frames dropped stale-preview` grows while cadence stays smooth: intended low-latency
  behavior; the device is slower than input but no old-frame backlog is accumulating.
- Buffer availability reaches zero: investigate ownership/pool starvation with the
  recorded queue and conversion durations.

## Protected corruption fix audit

`android/uvcandroid/src/main/jni/libuvc/src/stream.c` was not modified. Its SHA-256 before
and after this work is:

`904747BC986906CC511B78042FF44FCCBB5D89F692DFDC0131A74D564C66D871`

Confirmed preserved:

- UVC ERR packet invalidation
- malformed payload-header rejection
- missing-EOF/FID-transition rejection
- complete EOF-gated frame publication
- exact YUY2 source-size validation
- complete-frame-before-conversion behavior
- no partial/stale RGBX publication
- last-good-frame behavior when an invalid frame is dropped
- all native integrity counters

No incomplete or corrupt frame is admitted to improve FPS.

## Files changed

- `android/uvcandroid/src/main/jni/UVCCamera/UVCPreview.cpp`
- `android/uvcandroid/src/main/jni/UVCCamera/UVCPreview.h`
- `android/uvcandroid/src/main/jni/UVCCamera/registerUVCCamera.cpp`
- `android/uvcandroid/src/main/java/com/serenegiant/usb/UVCCamera.java`
- `android/uvcandroid/src/main/java/com/serenegiant/widget/UVCCameraTextureView.java`
- `android/app/src/main/java/com/rammy/aigun/camera/UvcArtifactDiagnostics.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/UsbCameraController.kt`
- `android/app/src/test/java/com/rammy/aigun/camera/NativeUvcIntegrityStatsTest.kt`
- `PROGRESS.md`

## Build result

- `testDebugUnitTest`: passed, 10 tests and zero failures
- native debug compilation: passed for the configured ABIs
- `assembleDebug`: passed
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Package: `com.rammy.aigun.debug`
- Version: `1.0.0-debug` (`versionCode` 2)
- Built: 2026-08-15 12:01:03 +05:30
- Size: 28,380,613 bytes
- SHA-256: `6CE8A3B308D43FDFE448AFC648213BA02668D4FA2FEF8C33635B6EAD6EF29136`
- APK Signature Scheme v2 and ZIP alignment: verified

## Required physical test

Install this exact debug APK on the affected Android 14 phone and run preview-only for at
least five minutes. Then repeat with recording and with a photo capture. Copy/export the
diagnostics after each run. Success requires continuous scene motion, no repeated
one-second freezes, no green/magenta corruption, low latency, and a stable USB connection.
