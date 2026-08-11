# Rammy AI Gun Architecture

## Goals and boundaries

Rammy AI Gun is an offline-first native camera monitor. The critical path is USB attach to first visible frame; account, backend, analytics, and network services are deliberately absent. Android is the primary platform. Apple support is capability-driven and never implies universal generic USB-camera support.

## Repository layout

```text
android/app/                 Kotlin Android application
  camera/                    USB/UVC lifecycle and stream state
  ui/                        Jetpack Compose monitor shell
ios/RammyAIGun/              SwiftUI application and AVFoundation capture layer
docs/                        test and operational documentation
shared-assets/               shared source artwork (future)
```

## Android

### Critical path

```text
USB attach / current device enumeration
  -> UVC class check
  -> UsbManager/UVCAndroid USB-device authorization
  -> UsbControlBlock
  -> libuvc negotiation (MJPEG, then YUY2 fallback)
  -> native camera thread
  -> OpenGL renderer
  -> AspectRatioTextureView
  -> first-frame metric
```

`UsbCameraController` owns the USB monitor, current USB control block, native UVC camera, retry state, and a `StateFlow<CameraConnectionState>`. USB work and camera negotiation are performed by the native library's camera thread. Compose observes state and never handles camera frames.

The first requested configuration is 1920x1080. UVC descriptors are inspected after opening the control block; controller fallback then tries 1280x720 and 640x480, preferring MJPEG and retaining YUY2 as a compatibility fallback. This provides broad fallback without hard-coding a vendor/product pair.

The manifest declares USB host as optional so unsupported devices install and receive a useful message. A class-based UVC device filter supports Android's attach-launch flow. Runtime detection also checks device interfaces because many UVC cameras are composite devices.

### Permission policy

No camera, storage, audio, location, or network runtime permissions are requested for the Android preview path. USB-device access uses Android's official per-device permission dialog; that is the only authorization this milestone needs. Normal denial is retryable, and the app does not bypass or auto-grant it.

### Performance policy

- No startup network or persistence work precedes preview.
- Preview frames stay in the native USB/OpenGL pipeline.
- Raw preview callbacks are disabled for the initial path, avoiding per-frame Kotlin allocation.
- Debug-only timing covers USB detection, permission-to-open, negotiation, and first frame.
- Teardown is idempotent across detach, surface destruction, and activity destruction.

## Apple

The iOS shell is SwiftUI with an `AVCaptureVideoPreviewLayer` bridge. `ExternalCameraManager` uses only AVFoundation discovery and connection notifications. It selects `.external` video devices when the OS exposes one, otherwise it reports an unsupported capability state. Capture-session configuration and start/stop execute on a dedicated serial queue.

## Later milestones

Photo capture, H.264 recording, MediaStore publishing, gallery, transforms, diagnostics, and full settings are intentionally downstream of physical-device validation of the first UVC preview.
