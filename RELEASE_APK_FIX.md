# Rammy AI Gun Release APK Fix

Date: 2026-08-13

## Result

A signed, zip-aligned, single-file Android release APK is available at:

`release-output/Rammy-AI-Gun-1.0.0.apk`

This APK is signed with the existing local Android debug keystore for sideload testing. It is not presented as the official Google Play upload identity. No official Rammy AI Gun release/upload keystore was present in the repository, the local Gradle configuration, or the checked local key locations.

## Exact root cause

The original artifact was:

`android/app/build/outputs/apk/release/app-release-unsigned.apk`

It was a structurally valid, zip-aligned, universal release APK, but it had no APK signing certificate. Before the fix, `apksigner verify --verbose --print-certs` returned:

`DOES NOT VERIFY`

`ERROR: Missing META-INF/MANIFEST.MF`

The release build type did not have a `signingConfig`. Android therefore rejected the APK as an invalid package. The failure was not caused by APK corruption, split packaging, ABI filtering, R8, the manifest, or the UVC native libraries.

No Android device was connected through ADB during this task, so the phone's exact `INSTALL_FAILED_*` result could not be captured. The user-reported system message was `App not installed as package appears to be invalid.`

## Signing fix

`android/app/build.gradle.kts` now supports an official release identity through external Gradle properties or environment variables:

- `RAMMY_RELEASE_STORE_FILE`
- `RAMMY_RELEASE_STORE_PASSWORD`
- `RAMMY_RELEASE_KEY_ALIAS`
- `RAMMY_RELEASE_KEY_PASSWORD`

No signing secrets or keystore files are committed. When all four official values are supplied, the release build uses the `rammyRelease` signing configuration.

Because no official Rammy key was available, this task's installable artifact was built with the explicit local-only switch:

`-PrammyLocalTestSigning=true`

That switch uses the existing Android debug signing configuration. Official release credentials take precedence if supplied. A normal release build without official credentials or the explicit local-test switch remains unsigned, preventing the local test identity from silently becoming the Play identity.

## Final APK details

- Application: Rammy AI Gun
- Package/application ID: `com.rammy.aigun`
- Version name: `1.0.0`
- Version code: `2`
- Minimum SDK: `26`
- Target SDK: `35`
- Main activity: `com.rammy.aigun.MainActivity`
- Packaging: one standalone/universal APK; not a split APK
- File: `release-output/Rammy-AI-Gun-1.0.0.apk`
- File size: `10,182,122` bytes
- SHA-256: `25B63E26D82BD4C911097B48915254E21FD676CC0CC2689754BC4D758D345C90`
- Gradle output retained at: `android/app/build/outputs/apk/release/app-release.apk`

## Signature after the fix

- Signature verifies: YES
- Signers: 1
- Certificate identity: `C=US, O=Android, CN=Android Debug`
- Certificate SHA-256: `05ba448f203af32e73e4493b3de9c991e2b9507fe4ddf0404c1ac5e7996e1099`
- APK Signature Scheme v1: false
- APK Signature Scheme v2: true
- APK Signature Scheme v3/v3.1: false
- APK Signature Scheme v4: false

Version 2 signing is valid for this application's minimum API level 26. V4 is an optional sidecar/install-time signature and is not required for a standalone sideload APK.

## ABI and UVC verification

The final APK contains native UVC/USB/JPEG/YUV libraries for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

Each ABI contains `libUVCCamera.so`, `libuvc.so`, `libusb1.0.so`, `libjpeg-turbo212.so`, and `libyuv.so`.

R8/minification remains enabled. It completed successfully, and the required native libraries remain packaged, so it was not changed.

## Manifest verification

The merged release manifest retains:

- `android.hardware.usb.host`
- `android.permission.CAMERA`
- `MainActivity`
- `MAIN` and `LAUNCHER`
- `android.hardware.usb.action.USB_DEVICE_ATTACHED`
- the USB device filter metadata

The manifest merge and release lint-vital checks completed without errors.

## Commands executed

- `apksigner verify --verbose --print-certs <apk>`
- `zipalign -c -v 4 <apk>`
- `aapt dump badging <apk>`
- ZIP entry inspection for `lib/<abi>/*.so`
- `gradlew.bat --no-daemon clean assembleRelease -PrammyLocalTestSigning=true`
- `gradlew.bat --no-daemon testReleaseUnitTest -PrammyLocalTestSigning=true`

The clean release build and release unit tests both exited successfully. The copied final APK was verified again after the clean build.

## Physical installation and signature compatibility

Physical installation, launch, and USB camera control tests were not performed because `adb devices -l` reported no connected devices.

If an older app with package `com.rammy.aigun` is installed under a different signing certificate, Android will reject an update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. In that confirmed situation, either sign with the original certificate or uninstall that old package once before installing this local-test build. Existing debug builds use `com.rammy.aigun.debug`, so they do not conflict with this release package.

Before publishing to Google Play, obtain and securely preserve the official Rammy upload/release keystore, provide the four `RAMMY_RELEASE_*` values externally, rebuild without `rammyLocalTestSigning`, and verify the resulting certificate.
