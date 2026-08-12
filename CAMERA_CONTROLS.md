# Camera controls implementation

Last updated: 2026-08-12

## Preserved camera architecture

The proven Android connection path is unchanged:

`UsbManager` enumeration and permission -> `CameraHelper` / UVCAndroid 1.0.13 -> native libuvc stream -> `FirstFrameTextureView` preview surface.

No USB permission, attach/detach, `openDevice()`, UVC negotiation, stream fallback, or camera library was replaced. Control features call the capture, recording, and renderer APIs already exposed by the active `CameraHelper` instance.

## Android implementation

### Photo

- The Photo button calls UVCAndroid `takePicture()`.
- Capture comes from the external-camera renderer, not an Android screen/UI screenshot.
- JPEG quality is 95.
- Only one capture may run at a time.
- The renderer's active 0/90/180/270 and mirror state is applied to the captured pixels.
- A short visual shutter and haptic are UI-only and never appear in the saved image.

Storage:

- Android 10+: MediaStore pending item -> `Pictures/Rammy AI Gun/RAMMY_IMG_yyyyMMdd_HHmmss_SSS.jpg` -> publish with `IS_PENDING=0`.
- Android 8/9: MediaStore item with the legacy public path and the runtime `WRITE_EXTERNAL_STORAGE` permission, which is capped at API 28 in the manifest.
- No broad storage permission or read-media permission is requested on modern Android.

### Video recording

- The Record button calls UVCAndroid `startRecording()` / `stopRecording()` on the already-open external camera.
- The library uses a MediaCodec encoder surface and MP4 muxer. It does not record the screen or convert each frame through Compose bitmaps.
- Output is video-only H.264/AVC MP4; audio remains disabled.
- Recording follows the negotiated camera resolution/FPS, with a resolution-appropriate bit rate.
- Preview stays attached and the USB/UVC stream is not reopened when recording starts or stops.
- Starting, recording, stopping, and idle are explicit states; repeated taps cannot start/stop twice.
- Active recording shows a red stop control and elapsed timer.
- App background and USB detach request a safe stop before camera cleanup. A completion timeout publishes a non-empty partial recording if the muxer callback cannot return after interruption; empty/failed outputs are removed.

Storage:

- Android 10+: MediaStore pending item -> `Movies/Rammy AI Gun/RAMMY_VID_yyyyMMdd_HHmmss_SSS.mp4` -> publish with `IS_PENDING=0`.
- Android 8/9: MediaStore item in the public Movies directory and a media scan.

### Preview transforms and controls

- Rotate cycles deterministically: 0 -> 90 -> 180 -> 270 -> 0.
- Rotation is sent to UVCAndroid's renderer and does not change Activity/device orientation.
- `FirstFrameTextureView` swaps the effective aspect ratio for 90/270 degrees.
- Fit shows the complete frame without stretching.
- Fill uses a centered aspect-preserving texture transform and crops edges; it never stretches the image.
- Fullscreen hides overlays and leaves the preview surface/session untouched. One camera-area tap restores controls.
- Hide controls removes the logo/top/bottom overlays. One camera-area tap restores them.
- Info opens a dismissible overlay with live device, VID/PID, stream, transform, recording, and app-version values.
- Both existing Settings buttons open the same existing USB diagnostics/settings overlay without stopping preview.
- Buttons provide standard pressed feedback; Photo, Record, and Rotate add haptics.
- Small state messages (`Photo saved`, `Video saved`, rotation angle, Fit/Fill) disappear automatically.
- Gallery remains visible and intentionally disabled for the separate Gallery milestone.

## Apple implementation

The existing AVFoundation external-camera capability path is preserved and extended with:

- `AVCapturePhotoOutput` for external-camera JPEG capture;
- `AVCaptureMovieFileOutput` with H.264 when available;
- add-only Photos authorization and `PHAssetCreationRequest` publication;
- preview rotation and Fit/Fill on the existing `AVCaptureVideoPreviewLayer`;
- record timer, safe background/disconnect stop, hide/show, Info, and Settings overlays.

iOS/iPadOS continues to expose only cameras that AVFoundation reports as `.external`. No unsupported USB capability is simulated.

## Files changed

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/rammy/aigun/MainActivity.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/CameraControlsState.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/CameraViewModel.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/FirstFrameTextureView.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/MediaStorePublisher.kt`
- `android/app/src/main/java/com/rammy/aigun/camera/UsbCameraController.kt`
- `android/app/src/main/java/com/rammy/aigun/ui/CameraScreen.kt`
- `ios/RammyAIGun/CameraMonitorView.swift`
- `ios/RammyAIGun/CameraPreview.swift`
- `ios/RammyAIGun/ExternalCameraManager.swift`
- `ios/RammyAIGun/Info.plist`

## Known limitations and physical test gate

- Android compilation and unit tests verify integration, but Gallery publication, OEM MediaStore behavior, MP4 playback, encoder throughput, and external-frame orientation require a physical UVC camera test.
- iOS cannot be compiled in this Windows workspace and needs Xcode plus a supported AVFoundation-exposed external camera.
- On iOS, video rotation is fixed when a recording starts. A rotation selected during an active recording updates preview immediately but applies to the next recording file.
- Android interrupted-video recovery publishes a partial file only if data exists after the encoder/muxer stop timeout. A hardware/codec failure can still make partial recovery technically impossible.
- Mirror/vertical-flip state exists in the Android transform model and renderer mapping, but no mirror/flip button is currently visible in the supplied camera UI, so no new button was added in this no-redesign task.

## Required physical test

1. Connect the known-working UVC camera and confirm live preview appears exactly as before.
2. Capture 20 photos; confirm preview never stops and every JPEG appears in the system Gallery without UI overlays.
3. Record and stop five MP4 files; confirm timers, smooth preview, Gallery visibility, and playback.
4. During preview, cycle 0/90/180/270/0 and verify no disconnect, freeze, stretching, or incorrect 90/270 aspect.
5. Capture a photo and a video after each rotation and verify saved orientation.
6. Toggle Fit/Fill repeatedly and verify letterbox versus centered crop.
7. Enter fullscreen and hide controls; tap the camera area to restore controls.
8. Open/close Info and both Settings buttons while streaming and recording.
9. Start recording, unplug the camera, and verify safe stop/disconnected state plus either a playable partial MP4 or a cleanly removed invalid output.
10. Start recording, background the app, return, and verify the recording finalized and the camera connection remains usable.
