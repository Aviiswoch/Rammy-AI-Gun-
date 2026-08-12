# Android UVC physical-device test

Physical hardware is required for this gate. A passing build does not prove that a phone, adapter, and camera can negotiate USB Host mode or stream UVC video.

## Equipment

- Android phone with USB Host support
- known-good OTG/USB Host adapter (not a charge-only adapter)
- standard UVC camera
- powered USB hub if the camera can exceed the phone's available USB power
- computer with `adb` for debug logs

## Fresh permission test

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` (debug application ID: `com.rammy.aigun.debug`).
2. Unplug the camera. Launch Rammy AI Gun and confirm it says “Connect your USB camera”.
3. Open the gear, then confirm `USB Host supported: YES` and `Connected USB devices: 0`.
4. Start logs:

   ```text
   adb logcat -c
   adb logcat -s RammyUsb RammyCameraMetrics
   ```

5. Connect the UVC camera through the OTG adapter.
6. Confirm the in-app CAMERA explanation appears only after a UVC device is detected. Press **Allow & Connect**, then grant Android CAMERA permission.
7. Grant the Android USB-device permission dialog.
8. Confirm there is no visit to USB Settings, no second Connect press, and the real external-camera preview starts automatically.
9. Open **Settings / Diagnostics / USB** and confirm:

   - camera detected: YES;
   - both permissions: GRANTED;
   - vendor/product and interface descriptors are populated;
   - selected UVC interface is populated;
   - device opened: YES;
   - resolution/FPS/format are populated;
   - first frame: YES.

## Reconnect tests

1. While previewing, unplug the camera. Confirm “Camera disconnected / Waiting for camera...” and no crash.
2. Reconnect it. If Android retained USB permission, preview must resume without another app button.
3. Repeat unplug/replug five times.
4. Force-stop the app, attach the camera, and accept Android's attach/open prompt if shown. Confirm the app opens and continues automatically.
5. Attach the camera before launching the app; launch and confirm enumeration and automatic continuation.
6. Deny USB permission once. Confirm the app says USB camera permission is required and **Allow & Connect** retries the official dialog without opening USB Settings.
7. Deny CAMERA permission. Confirm it can be retried in-app. If Android permanently blocks another runtime dialog, only **Open Settings** should appear.

## Failure evidence

If preview does not start, tap the gear, use **Copy diagnostics**, and save the `adb logcat` output. The decisive stages are:

```text
[USB] Host supported=true
[USB] Devices found=1
[USB] Device name=... VID=... PID=...
[USB] Interface 0 class=14 subclass=1 ...
[USB] Interface 1 class=14 subclass=2 ...
[USB] UVC camera detected=true
[USB] Permission=false
[USB] Requesting permission
[USB] Permission granted=true
[USB] openDevice=success
[UVC] Trying 1280x720 MJPEG 30fps
[UVC] Stream negotiated 1280x720 MJPEG 30fps
[UVC] First frame received
```

Interpretation:

- host supported YES, devices 0: OTG role/cable, camera power, or hardware enumeration problem;
- devices present, camera detected NO: device does not expose a standard USB video interface;
- USB permission NOT GRANTED: Android USB-device permission was denied or not delivered;
- permission GRANTED, `USB_OPEN_FAILED`: Android could not open the enumerated device;
- device opened YES, no negotiated stream: UVC descriptor/configuration or native driver issue;
- negotiated stream, first frame NO: preview surface/frame-delivery issue.
