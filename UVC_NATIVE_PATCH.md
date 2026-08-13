# UVCAndroid Native Source Integration

Date: 2026-08-13

## Previous dependency

Rammy AI Gun previously consumed this Maven artifact directly from `android/app/build.gradle.kts`:

```text
com.herohan:UVCAndroid:1.0.13
```

The cached AAR SHA-256 was:

```text
4F1295A6C6B5E8590FB7DDE3C60F30A94166DB45C2D07EFA63C85D5DA8BFFF17
```

That AAR bundled the Java camera layer and native UVC libraries, but the public API exposed only decoded frames. It did not expose payload ERR/FID/EOF state, raw assembled YUY2 length, or transfer failures. Therefore the application callback could observe a full-size RGBX buffer without knowing that its source YUY2 frame had been incomplete.

No UVCAndroid source was present in this repository before this patch.

## Exact source selected

The source module is vendored at:

```text
android/uvcandroid
```

It is the upstream `libuvccamera` module from `shiyinghan/UVCAndroid`, tag `1.0.13`, commit:

```text
6018c7f20238832929cf61427427bb7828056260
```

`android/uvcandroid/UPSTREAM_LICENSE` preserves the upstream top-level license. The module contains its original Java/JNI, libusb, libuvc, libjpeg-turbo, and libyuv sources. The publishing-only Gradle plugin/configuration was removed; Android library compilation remains intact. Build configuration is aligned with this app (`compileSdk 35`, Java 17, minSdk 26), and native compilation is pinned to the installed complete NDK `27.1.12297006`.

## Integration change

`settings.gradle.kts` now includes `:android:uvcandroid`, and the app uses:

```text
implementation(project(":android:uvcandroid"))
```

instead of the Maven AAR. No second UVC library was introduced. Package names and the existing `CameraHelper`, `CameraConnectionService`, `UVCCamera`, renderer, photo, and recording APIs remain the same, so USB discovery, permission, connection, and UI code retain the known-working path.

## Isolation and rollback

The known-working pre-patch state is Git commit `43f55bd` (`vision guard`). The integration is isolated to the new source module, one dependency replacement, native integrity guards, JNI diagnostics exposure, and debug diagnostics fields. Reverting those changes restores the exact binary-AAR path.

No application ID, version code/name, USB permission flow, stream selection order, or resolution fallback was changed.
