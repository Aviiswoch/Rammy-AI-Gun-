# Android USB/OTG connection root-cause report

## Scope

This report covers the Android connection path as it existed before the USB fix. The two supplied screenshots were used as evidence, but no physical UVC camera has been tested in this development environment.

## Confirmed findings

1. **Rammy AI Gun does not explicitly open Android USB Settings.** A project-wide search found no `ACTION_USB_SETTINGS`, USB preferences intent, `Settings.ACTION_*` USB intent, or generic settings `startActivity` call in the camera flow. Inspection of the UVCAndroid 1.0.13 bytecode also found an official `UsbManager.requestPermission()` flow, not a settings-page launch.

2. **The app delegated the entire USB permission transaction to UVCAndroid.** `UsbCameraController.allowAndConnect()` called `CameraHelper.selectDevice()`. The library then created its own private permission broadcast action and `PendingIntent`. The application could not observe or diagnose the request, grant, denial, or the subsequent `UsbManager.openDevice()` result.

3. **The app did not automatically request permission when a camera was attached.** It changed to a `Detected` state and waited for the user to press a second button. This contradicts the required attach -> permission -> open -> preview flow.

4. **Different failures were collapsed into one misleading state.** UVCAndroid's `onCancel()` callback became `PermissionDenied`, and the UI displayed “Camera access is required / USB access is required”. That callback does not prove that Android's CAMERA runtime permission was the cause; it can also represent a rejected/cancelled USB-device permission request or a failed library permission/open transaction.

5. **The application removed CAMERA permission from the merged manifest.** Its manifest explicitly removed the transitive UVC library's `android.permission.CAMERA` declaration. There was therefore no in-app CAMERA runtime-permission path for devices/library versions that require it.

6. **There was insufficient host-mode evidence.** Discovery used the helper library's device list and callbacks rather than logging `UsbManager.deviceList`. The app did not log device/interface descriptors, distinguish device-level from interface-level USB video class, verify `FEATURE_USB_HOST`, or record `openDevice()` success. Consequently the existing error screen could not distinguish:

   - no host enumeration / OTG-role or cable issue;
   - insufficient camera power;
   - non-UVC USB device;
   - USB-device permission denial;
   - `openDevice()` failure;
   - UVC stream negotiation failure.

7. **Composite UVC cameras were not classified by application code.** Many cameras have USB class `0` or a composite class at device level and expose class `14` (USB Video) only on one or more interfaces. The former controller had no native interface-level classifier or log.

## Why the USB Settings screenshot appeared

There is no code path in this repository that launches that page, so removing a settings intent is not the applicable fix. The screenshot is Android/OEM USB-role UI. It can be opened by the system USB notification, by the user, or by OEM behavior when USB role negotiation does not result in the phone becoming host. “Connected device” controlling USB is evidence that this particular connection was not currently presented as a normal enumerated host-side camera connection.

The screenshot alone cannot establish whether the cable/adapter, camera power, or OEM role negotiation caused that state. Android applications cannot silently change the USB role with supported public APIs. The corrected app therefore uses USB Host APIs, never opens USB Settings during normal connection, and reports whether a device actually enumerated.

## UVC library decision

The existing dependency is `com.herohan:UVCAndroid:1.0.13`. Inspection confirms that it:

- uses Android USB Host APIs and libuvc/native camera code;
- expects an enumerated `UsbDevice`;
- calls `UsbManager.requestPermission()` internally when needed;
- opens the UVC device after permission;
- does not intentionally launch Android USB Settings.

It is not being replaced in this focused fix. The application will own device enumeration, runtime CAMERA permission, USB permission, state, and diagnostics, then hand an already-authorized device to the existing UVC streaming pipeline. This removes the opaque part of the flow without replacing the already-integrated native preview implementation.

## Fix strategy

- Restore the narrowly scoped CAMERA permission and request it only after a UVC device is found and before starting this library's camera path.
- Register an app-owned attach/detach/USB-permission receiver.
- Enumerate `UsbManager.deviceList` at start/resume and classify UVC at both device and interface level.
- Request USB permission with an explicit package-scoped `PendingIntent` action: `com.rammy.aigun.USB_PERMISSION`.
- On grant, verify `UsbManager.openDevice()` and immediately continue into `CameraHelper.selectDevice()`; no second Connect press.
- Use an explicit connection state machine and suppress duplicate permission/open attempts.
- Log descriptors, permissions, open result, negotiation attempts, first frame, and connection timing.
- Preserve automatic fallback stream negotiation in the existing UVC preview path.

## Verification limit

The build and automated tests can verify the code path and compilation. Only a physical Android phone, OTG host adapter, and compatible UVC camera can verify enumeration, the OEM permission dialogs, native stream negotiation, latency, and the first real external-camera frame. Physical success must not be claimed until that test is completed.

## Implementation verification (2026-08-12)

- `testDebugUnitTest`: passed
- `assembleDebug`: passed
- merged debug manifest: USB Host optional and CAMERA permission present
- unrelated legacy audio/storage permissions: absent from the merged manifest
- debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- physical UVC preview: pending device test

The exact physical test and log-capture procedure is in `docs/ANDROID_USB_PHYSICAL_TEST.md`.
