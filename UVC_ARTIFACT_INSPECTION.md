# UVC Green/Magenta Artifact Inspection

Date: 2026-08-13  
Scope: inspection and debug instrumentation only; no artifact suppression or stream-policy change

## Executive finding

The evidence points most strongly to an incomplete or damaged UVC frame reaching UVCAndroid's decoder/render pipeline. The app does not assemble UVC packets itself. It uses the prebuilt `com.herohan:UVCAndroid:1.0.13` AAR (SHA-256 `4F1295A6C6B5E8590FB7DDE3C60F30A94166DB45C2D07EFA63C85D5DA8BFFF17`), whose native libuvc code owns packet transfers and frame assembly.

Inspection of the official 1.0.13 source (tag commit `6018c7f20238832929cf61427427bb7828056260`) found this exact failure opportunity:

1. An isochronous packet with a nonzero status is skipped, but the current frame is not marked invalid.
2. A payload with the UVC `ERR` flag is skipped, but the current frame is not marked invalid.
3. If FID changes while bytes are accumulated, native libuvc publishes those bytes by swapping buffers even though its own comment says the prior frame had no EOF.
4. The downstream `UVCPreview::uvc_preview_frame_callback` incomplete/unexpected-frame guard is present but commented out.
5. For YUYV, conversion stops at `in->data_bytes`, returns success, and leaves the unwritten part of a reused RGBX output frame untouched. The full RGBX surface is then posted. That can produce sharp rectangular areas containing stale color data.
6. For MJPEG, the library does not prevalidate SOI/EOI before TurboJPEG. A frame with valid outer structure but missing/corrupted entropy data can decode into block/stripe corruption; a fatal decode error is dropped, but native counters are not exposed.

The attached images contain large, sharply bounded magenta/green areas and fine horizontal striping. That appearance is consistent with partial/stale decoded image regions and is not characteristic of the app's Fit/Fill/rotation matrices. The playback triangle in the images suggests these may be screenshots of a saved video; if confirmed, corruption is upstream of the final preview-only `TextureView`, because preview and recording share the same decoded primary texture.

This is a ranked diagnosis, not a claimed fix. No physical phone was connected through ADB, and no Android 15/16 runtime diagnostics were supplied yet. The debug build created by this task is intended to obtain that correlation evidence.

## What was not changed

- USB attach/detach detection
- CAMERA or USB permission flow
- `UsbManager.openDevice()` behavior
- UVCAndroid dependency or native `.so` files
- mode ranking, requested resolution, requested FPS, or format fallback
- frame acceptance/discard policy
- preview, capture, recording, rotation, Fit/Fill, or normal camera UI behavior
- application ID, branding, logo, version name, or version code

The added post-decode observer is enabled only when `BuildConfig.DEBUG` is true. Release builds do not attach it.

## Exact current pipeline

```text
Android UsbDevice
  -> UsbCameraController enumeration/classification
  -> UsbManager.openDevice() probe (opened and immediately closed)
  -> CameraHelper.selectDevice()
  -> CameraConnectionService / CameraInternal
  -> USBMonitor UsbControlBlock / UsbDeviceConnection file descriptor
  -> UVCCamera.nativeConnect()
  -> native libusb + libuvc
  -> selected VideoStreaming interface/alternate endpoint
  -> libusb bulk or isochronous transfers
  -> _uvc_stream_callback()
  -> _uvc_process_payload() UVC header parsing and frame assembly
  -> _uvc_swap_buffers() / _uvc_populate_frame()
  -> UVCPreview::uvc_preview_frame_callback()
  -> independent native frame-pool copy
  -> MJPEG: TurboJPEG -> RGBX
     YUYV: uvc_yuyv2rgbx() -> RGBX
  -> copyToSurface() -> CameraRendererHolder primary ANativeWindow
  -> RendererHolder OpenGL external texture
      -> preview slave Surface
      -> photo capture branch
      -> MediaCodec recording input Surface
  -> FirstFrameTextureView (UVCCameraTextureView)
  -> SurfaceTexture.updateTexImage()
  -> GLDrawer2D -> TextureView/EGL display
  -> Compose AndroidView container
```

### Rammy source owners

- `android/app/src/main/java/com/rammy/aigun/camera/UsbCameraController.kt`
  - enumeration, permission/open handoff, mode ranking, CameraHelper lifecycle, preview surface, photo/recording calls, transforms
- `android/app/src/main/java/com/rammy/aigun/camera/UvcClassifier.kt`
  - device/interface UVC classification
- `android/app/src/main/java/com/rammy/aigun/camera/FirstFrameTextureView.kt`
  - final TextureView surface, first/rendered-frame observation, Fit/Fill matrix
- `android/app/src/main/java/com/rammy/aigun/ui/CameraScreen.kt`
  - Compose `AndroidView` host and existing developer diagnostics dialog
- `android/app/src/main/java/com/rammy/aigun/camera/CameraControlsState.kt`
  - rotation/mirror/display state
- `android/app/src/main/java/com/rammy/aigun/camera/MediaStorePublisher.kt`
  - output publication only; not in the live pixel path

### UVCAndroid 1.0.13 source owners

- `CameraHelper.java`, `CameraConnectionService.java`, `CameraInternal.java`
- `UVCCamera.java` and JNI `registerUVCCamera.cpp`
- `libuvc/src/stream.c`
  - USB transfer callback, UVC payload header parsing, FID/EOF/ERR handling, frame assembly
- `UVCCamera/UVCPreview.cpp`
  - native frame pool, MJPEG/YUYV conversion, primary ANativeWindow writes, callback queue
- `UVCCamera/ConvertHelper.cpp`
  - TurboJPEG MJPEG-to-RGBX decode
- `libuvc/src/frame.c`
  - frame copies and YUYV-to-RGBX conversion
- `CameraRendererHolder.java` / `RendererHolder.java`
  - shared OpenGL primary texture and preview/photo/recording slave surfaces
- `UVCCameraTextureView.java`
  - final preview TextureView renderer

Official source inspected: `https://github.com/shiyinghan/UVCAndroid`, tag `1.0.13`.

## Negotiated mode: what is known and what needs runtime data

Rammy ranks modes in this order:

1. MJPEG before uncompressed
2. 1920x1080 before 1280x720 before 640x480
3. highest advertised FPS within a matching format/resolution
4. additional supported modes up to 1920x1080 as fallbacks

Therefore, if this camera advertises 1920x1080 MJPEG, that is the first attempted mode. It is not valid to claim that it is the actual mode on the affected phone without the runtime `previewSize` result. The new debug logs report the actual negotiated `Size` after `onCameraOpen`, including descriptor type, resolution, FPS, FPS list, calculated frame interval, and all advertised modes.

For uncompressed YUY2:

```text
expected raw bytes = width * height * 2
approximate raw bandwidth = width * height * 2 * FPS
```

For MJPEG, raw frame bytes and bandwidth are variable. The binary Java API does not expose `dwMaxVideoFrameSize`, actual compressed frame bytes, or `dwMaxPayloadTransferSize`.

Android USB interface descriptors are now logged with interface index/ID, alternate setting, endpoint address/direction/type, maximum packet size, and interval. The exact alternate endpoint selected by native libuvc is not exposed; diagnostics label a single/type-consistent candidate as inferred rather than confirmed.

## UVC payload/frame-assembly findings

### Fields actually parsed by native libuvc

- payload header length
- FID
- EOF
- ERR
- PTS
- SCR
- optional metadata bytes

### Unsafe completeness behavior found

- A malformed header whose declared length exceeds the payload is rejected.
- A payload carrying `UVC_STREAM_ERR` is rejected, but already accumulated frame bytes remain live.
- An isochronous packet with a nonzero packet status is skipped, but the containing frame is not invalidated.
- A changed FID with accumulated bytes calls `_uvc_swap_buffers()` and publishes the previous bytes. This is explicitly the missing-EOF case.
- Reaching `dwMaxVideoFrameSize` also publishes the frame.
- No per-frame `corrupt/incomplete` flag is propagated to `UVCPreview`.
- No exact YUYV byte-size check is active before decode.
- No MJPEG SOI/EOI check is active before decode.

This means a short/lost/error payload can produce an assembled frame that downstream code treats as complete.

## MJPEG inspection

UVCAndroid calls `tjDecompressHeader3()` and `tjDecompress2()` on the accumulated byte count. It validates decoded dimensions and drops a fatal TurboJPEG return. It does not explicitly validate:

- first two bytes `FF D8` (SOI)
- final marker `FF D9` (EOI)
- completeness of all entropy-coded scan data

The public `PIXEL_FORMAT_RAW` callback is not compressed UVC data: native code converts the already decoded RGBX frame back to YUYV. Consequently, Rammy cannot safely count `mjpeg_invalid_soi`, `mjpeg_invalid_eoi`, actual compressed bytes, or decoder failures through the published AAR API. Doing so requires a controlled diagnostic build of the same native library, which was intentionally not introduced in this inspection.

## YUY2/YUYV inspection

UVCAndroid requests `UVC_FRAME_FORMAT_UNCOMPRESSED`/YUYV for uncompressed descriptors and converts it with `uvc_yuyv2rgbx()`.

Expected layout:

- byte order: Y0 U Y1 V (YUYV/YUY2)
- raw row bytes: `width * 2`
- raw frame bytes: `width * height * 2`
- RGBX output row bytes: `width * 4`

Critical observation: `uvc_yuyv2rgbx()` bounds its conversion by `in->data_bytes`, but returns success even when the input contains fewer than `width * height * 2` bytes. Its output is a reused frame-pool allocation and is not cleared. The native surface copy then posts the full output dimensions. If the affected phone negotiates YUY2, this is a direct mechanism for current-frame data followed by stale rectangular regions from an older frame.

No evidence was found that Rammy treats YUY2 as UYVY. Rotation does not change width/stride in the raw decoder; rotation is applied later in OpenGL.

## Buffer ownership and race inspection

The direct USB-write-versus-decoder-read race is less likely than incomplete-frame acceptance:

- libuvc swaps assembly and hold buffers under `cb_mutex`.
- `_uvc_populate_frame()` copies the hold buffer into a callback frame.
- `UVCPreview::uvc_preview_frame_callback()` duplicates that frame again into its own frame pool.
- preview queue access is protected by `preview_mutex`.
- decoded RGBX frames move to a latest-frame capture queue protected by `capture_mutex`.
- native windows are locked/unlocked around CPU writes.

The app's debug callback receives a JNI direct `ByteBuffer` synchronously. The inspector reads it only during the callback and retains no buffer reference. It records Java buffer-object identities, but the public API does not expose native addresses, write start/completion, decoder start, or GPU completion fences. Therefore native pointer reuse cannot be absolutely disproved from Java, but source ownership makes an obvious concurrent overwrite unlikely.

## Rendering inspection

There are multiple native/GPU stages, not a Compose bitmap pipeline:

1. CPU RGBX writes into the CameraRendererHolder primary `ANativeWindow`.
2. RendererHolder updates an external OES texture and draws it to preview/recording/photo slave surfaces.
3. UVCCameraTextureView receives the preview slave surface and performs another `SurfaceTexture.updateTexImage()`/OpenGL draw into the TextureView.
4. Compose only hosts the View; it does not convert frames.

Potential renderer issue found: native `copyToSurface()` does not assert that frame width/height/step equal the locked ANativeWindow buffer geometry. Its row-copy branch uses `buffer.width` rather than `frame->step` for source offsets. If an OEM returns unexpected surface geometry/stride, the result can be device-specific rectangular corruption. This is plausible but ranked below incomplete-frame handling until decoded-frame diagnostics are captured.

Rotation/mirror are applied in CameraRendererHolder OpenGL matrices; Fit/Fill is applied later to the final TextureView. None changes raw decoder stride or raw frame assembly. If `suspiciousDecodedColorFrames` rises at rotation 0 and Fit, transforms are exonerated. If decoded observations remain clean but only display flashes, the renderer/surface path moves to the top of the ranking.

## Recording and photo diagnostic meaning

Preview, image capture, and MediaCodec recording branch from CameraRendererHolder's shared primary texture.

- Corruption in saved recording and preview: fault is before or in the shared primary renderer; final TextureView-only corruption is ruled out.
- Recording clean but preview corrupt: final preview slave/UVCCameraTextureView path is implicated.
- Photo and recording clean while preview flashes: transient final render/surface issue is likely.
- `suspiciousDecodedColorFrames` correlates with flash: corruption exists in post-decode RGBX before final display transforms.
- Visible/recorded artifact with no decoded heuristic event: heuristic may have missed it, or corruption occurs in `copyToSurface`/shared renderer after the debug callback's RGBX source.

The attached images include a playback icon, so they appear to be frames viewed in a video player. This is supporting visual evidence, not proof that the underlying saved MP4 contains the corruption; confirm by playing/copying the original recording and recording the timestamp.

## Debug diagnostic mode added

Debug builds now provide:

- manufacturer, model, Android release, SDK, hardware, and CPU ABIs
- camera VID/PID through existing USB diagnostics
- all advertised modes and actual negotiated mode
- VideoStreaming endpoint candidates, type, maximum packet size, and interval
- inferred endpoint type where descriptors permit; never presented as native confirmation
- frame interval
- expected YUY2 raw bytes or variable MJPEG marker
- expected and actual post-decode RGBX callback bytes
- decoded RGBX frames observed
- final TextureView frames rendered
- decode/render backlog estimate (explicitly not a definitive dropped-frame count)
- decoded-size mismatches
- debug frame IDs and callback Java buffer-object IDs
- diagnostic callback/read and render timestamps
- transform and recording state
- sampled sudden green/magenta block heuristic
- `[CORRUPT_FRAME]` logging without discarding or altering the frame

The color detector samples 16x9 points and compares them with the previous decoded frame. It reports only abrupt dominant green/magenta changes. It is a correlation heuristic, not proof of corruption, and it performs no full-frame copy.

The existing `Settings / Diagnostics / USB` dialog includes these values. `Copy diagnostics` remains available. Debug builds additionally expose `Export`, which writes `uvc-diagnostics.txt` to `Downloads/Rammy AI Gun` on Android 10+ (app-specific Documents storage on Android 8/9).

Recommended Logcat command:

```text
adb logcat -c
adb logcat -v threadtime RammyUvcArtifact:D RammyUsb:D RammyCameraMetrics:D *:S
```

Relevant examples:

```text
[UVC] Selected format=MJPEG type=7
[UVC] Resolution=1920x1080
[UVC] FPS=30
[USB] EndpointCandidate=... type=ISOCHRONOUS maxPacketSize=... interval=...
[UVC_DIAG] frameId=... bufferObjectId=... diagnosticReadStart=... diagnosticReadComplete=...
[CORRUPT_FRAME] frame=... reason=DECODED_RGBX_SIZE_MISMATCH ...
[CORRUPT_FRAME] frame=... reason=COLOR_BLOCK_HEURISTIC_ONLY ...
```

### Counters that remain unavailable

The binary AAR exposes no packet or assembled-compressed-frame callback, so these cannot be truthfully populated at the app boundary:

- `uvc_packets_total`
- `uvc_packets_error_flag`
- `frames_started`, native `frames_completed`, `frames_incomplete`, `missing_eof`, `unexpected_fid_change`
- native `frames_discarded`
- actual compressed `mjpeg_frame_bytes`, invalid SOI/EOI, native decode failures
- `usb_transfer_errors`, short transfers, timeouts, zero-length packets, request/resubmit failures
- selected native alternate setting and committed `dwMaxPayloadTransferSize`

The diagnostics explicitly say `UNAVAILABLE`; they do not report zero. The exact code paths needing native counters are identified above for a follow-up diagnostic build of the same library if post-decode correlation is insufficient.

## Two-device test matrix

Use the same camera, powered state, OTG cable, debug APK, orientation, and test duration.

Debug APK:

`android/app/build/outputs/apk/debug/app-debug.apk`

The debug package is `com.rammy.aigun.debug`, so it can coexist with the release package.

### Baseline procedure per device

1. Clear Logcat and start the filtered command above.
2. Launch the debug app and connect the UVC camera.
3. Leave rotation at 0, mirror off, and Fit mode for two minutes.
4. When an artifact is seen, note the wall-clock time and whether `[CORRUPT_FRAME]` appears.
5. Record 30-60 seconds while artifacts are visible; play the saved MP4 frame-by-frame.
6. Capture at least five photos, including immediately after a visible flash where possible.
7. Open Settings/Diagnostics, copy the text, and export `uvc-diagnostics.txt`.
8. Repeat rotation 90/180/270 and Fit/Fill only after the baseline.
9. Repeat for at least ten minutes to compare rates rather than one event.

### Comparison table

| Field | Device A: Samsung S23 / Android 16 | Device B: Samsung / Android 15 |
|---|---|---|
| Exact model / SDK / hardware | capture | capture |
| VID / PID | capture | capture |
| Actual format/type | capture | capture |
| Resolution / FPS / interval | capture | capture |
| Endpoint type / packet size | capture | capture |
| Expected/actual decoded bytes | capture | capture |
| Decoded frames | capture | capture |
| Rendered frames / backlog | capture | capture |
| Size mismatches | capture | capture |
| Suspicious color frames | capture | capture |
| Artifact count and timestamps | expected none | capture |
| Artifact present in MP4 | capture | capture |
| Artifact present in photos | capture | capture |

The app currently has no completed manual quality selector. It automatically falls back only after open/configuration failure. No temporary mode switch was added because that would alter the baseline selection. After the baseline is captured, a hidden diagnostic-only mode override can be considered separately if required.

## Root-cause ranking

### 1. Incomplete/damaged UVC frames are published and decoded — HIGH confidence

Evidence:

- Native source publishes accumulated bytes on FID change without EOF.
- ERR packets and bad isochronous packets are skipped without invalidating the frame.
- Downstream incomplete-frame validation is commented out.
- Rectangular stale/corrupted regions match partial image data reaching conversion/output.
- Same phone/camera works better in another app, consistent with stricter validation or safer transfer policy.

Missing evidence: native packet counters and runtime correlation on Device B.

### 2. Partial YUY2 conversion leaves stale RGBX regions — HIGH if Device B negotiates YUY2; LOW otherwise

Evidence:

- Converter bounds work by actual input bytes but reports success.
- Reused output buffer is not cleared.
- Full output surface is posted.
- This directly predicts sharp current/stale rectangular boundaries and colored scan lines.

Decisive test: actual negotiated format and post-decode suspicious-frame correlation.

### 3. Damaged MJPEG frame passes structural/decode checks with block corruption — MEDIUM-HIGH if Device B negotiates MJPEG

Evidence:

- No SOI/EOI prevalidation.
- Packet loss may remove internal JPEG entropy while a later EOF/EOI remains.
- Large chroma blocks/stripes are compatible with damaged JPEG data.

Decisive test: native compressed-frame validation/counters in a follow-up same-library diagnostic build.

### 4. Primary ANativeWindow dimension/stride handling differs on the Android 15 device — MEDIUM confidence

Evidence:

- `copyToSurface()` assumes buffer geometry and uses buffer width in source stride math.
- GPU/Surface implementations and padding can differ by device/OS.
- This shared stage feeds recordings and preview.

Decisive test: decoded RGBX stays clean while recording/preview share corruption; then log native frame/buffer width, height, format, and stride.

### 5. Higher bandwidth/mode pressure exposes transfer loss — MEDIUM confidence

Evidence:

- Rammy prioritizes highest-FPS MJPEG at 1080p when advertised.
- Different USB host controllers, kernels, power delivery, and native transfer scheduling can change packet-loss rate.
- Another app can choose 720p/lower FPS or tune transfer queues differently.

This is a trigger, not sufficient root cause: robust frame validation should prevent one lost packet from becoming a visible corrupt frame.

### 6. Decoder/USB frame-pool reuse race — LOW confidence

Evidence against:

- Multiple mutex-protected buffer swaps and explicit frame copies exist before decode.
- Capture queue ownership is explicit.

Native address/fence data is not exposed, so confidence is not zero.

### 7. Rotation/Fit/Fill transformation math — LOW confidence

Evidence against:

- Transform occurs after decode/assembly.
- It changes matrices/aspect layout, not raw row stride or byte order.
- The observed corruption is localized pixel content, not a consistent geometric distortion.

## Why Android 15 can differ from Android 16

The Android version alone is not enough to identify the cause. The two phones may differ in SoC USB host controller, kernel USB stack, OEM usbfs behavior, scheduler timing, power delivery, GPU/Surface implementation, and buffer stride. UVCAndroid's missing completeness validation makes those lower-level differences visible: Device A can deliver every packet and never trigger the flaw, while Device B loses/flags an occasional packet and allows the damaged frame downstream. A different ANativeWindow stride policy is the secondary device-specific path to test.

No Samsung-specific behavior is hardcoded or recommended.

## Why another OTG app can show fewer artifacts

The observation is evidence that stable software behavior is possible with this camera/phone combination. The other app may:

- choose lower bandwidth, FPS, resolution, or MJPEG instead of YUY2
- use different libusb transfer counts/sizes or endpoint alternate settings
- mark the whole frame invalid after one packet error
- require EOF and consistent FID
- validate exact YUY2 frame size
- validate JPEG SOI/EOI and decoder status
- retain the last good frame rather than presenting an incomplete one
- use a different surface upload/stride implementation

Its code is unavailable, so none of these is claimed as fact.

## Required questions answered

1. **Exact format?** Code prioritizes MJPEG; actual device format was not present in supplied evidence. The debug build now logs the post-open `previewSize` format/type exactly.
2. **Resolution/FPS?** Code first attempts highest-FPS 1920x1080 when advertised, then 720p/480p. Actual values require the new Device B log.
3. **Are frame sizes correct?** Not proven on hardware. Native source does not enforce exact uncompressed size. Debug build checks post-decode RGBX size; raw compressed size remains unexposed.
4. **UVC error/incomplete frames?** Native code can create them, but occurrence counts require native instrumentation or correlation evidence.
5. **Malformed MJPEG?** No SOI/EOI validation exists; occurrence is not yet measured.
6. **USB shorts/timeouts?** Native transfer code handles/skips statuses but does not expose counters to Rammy; occurrence is unknown.
7. **Can renderer receive incomplete data?** Yes. Source proves incomplete accumulated frames can be published; YUYV conversion can post a full surface with a stale tail.
8. **Unsafe buffer reuse?** No obvious concurrent reuse was found; pool reuse without clearing becomes unsafe specifically when conversion writes only a partial output.
9. **Before or after decoding?** Not yet measured. The debug decoded-frame heuristic separates post-decode source corruption from later rendering corruption.
10. **In recordings?** The playback overlay suggests it may be, but the original MP4 must be checked. The diagnostic meaning is documented above.
11. **Why Android 15 only?** Likely different USB transfer reliability/timing or surface stride exposes a latent validation defect; Android version alone is not proven causal.
12. **Why fewer artifacts in another app?** Likely safer mode/transfer tuning or stronger incomplete-frame rejection; software improvement is demonstrably plausible.
13. **Most likely root cause?** Incomplete/damaged UVC frames accepted by native assembly/decoder, with stale partial RGBX especially likely in YUY2.
14. **Fixable in software?** Yes. Complete-frame validation and native corruption propagation/discard are precise software fixes; endpoint/mode tuning may reduce the trigger.
15. **Exact next fix to attempt?** First capture the Device A/B diagnostic baseline. If decoded corruption correlates, patch the same native UVCAndroid pipeline to mark a frame corrupt on ERR/bad transfer, discard accumulated data on unexpected FID/missing EOF, require exact YUY2 size, and validate MJPEG boundaries before decode. If decoded callbacks stay clean, instrument/fix `copyToSurface()` geometry/stride before touching USB negotiation.

## Verification completed in this task

- Official UVCAndroid 1.0.13 Java/JNI/libuvc source inspected
- `:android:app:compileDebugKotlin`: passed
- `:android:app:testDebugUnitTest`: passed
- `:android:app:assembleDebug`: passed
- `:android:app:compileReleaseKotlin`: passed
- New RGBX inspector tests cover size mismatch, sudden magenta detection, and stable green-scene non-detection
- `git diff --check`: passed
- No ADB device was connected; physical reproduction remains required

## Stop condition

No final fix, mode downgrade, frame discard, library replacement, Samsung workaround, or artifact filter was implemented. The next action is to collect and compare the two device diagnostic bundles and original saved MP4 behavior.
