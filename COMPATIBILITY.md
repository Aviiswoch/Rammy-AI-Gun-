# Compatibility

## Android baseline

- Minimum SDK: Android 8.0 / API 26
- Target and compile SDK: API 35
- Required at runtime: device support for `android.hardware.usb.host`
- Camera class: USB Video Class devices reported through device class or interface class `0x0E`
- Native ABIs supplied by the initial UVC dependency: arm64-v8a, armeabi-v7a, x86, x86_64
- Initial preferences: 1080p, 720p, then 480p; MJPEG with YUY2 fallback

Installability does not guarantee OTG power delivery, OEM USB-host correctness, or a camera's descriptor/format compliance. Powered hubs may be required for high-current cameras.

## Apple baseline

- Deployment target: iOS/iPadOS 17.0
- API: AVFoundation only
- External video is used only when `AVCaptureDevice.DeviceType.external` discovery returns a device

Generic USB UVC support is not claimed for every iPhone or iPad. Unsupported hardware/OS combinations remain installable and show a compatibility message.

## Physical validation status

No physical USB camera has been tested in this workspace. See `docs/UVC_TEST_CHECKLIST.md` for the required acceptance matrix. A device must not be marked compatible until a human records device model, OS, camera VID/PID, cable/hub, negotiated mode, first-frame time, and stability result.

## Release risks to validate

- USB permission behavior across Android OEMs
- composite UVC/UAC cameras
- detach during negotiation and recording
- USB 2 bandwidth at 1080p
- insufficient bus power
- Android 15+ 16 KB native-library page-size compatibility
- Apple external device exposure on target hardware and OS

