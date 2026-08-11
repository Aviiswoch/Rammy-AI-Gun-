# Implementation Plan

Each milestone ends with a build, tests, `PROGRESS.md` update, and an explicit distinction between automated verification and physical-device verification.

## 1. Architecture and monitor shell

- Native Android and Apple projects
- Compose/SwiftUI dark glass monitor UI
- Explicit startup and compatibility states

Exit: projects are structurally ready and Android shell builds.

## 2. Android attach/detach

- USB host capability check
- broad UVC interface classification
- hot-plug and existing-device enumeration
- safe disconnected state

Exit: automated classifier tests pass; attach lifecycle is ready for device testing.

## 3. Android permission and first UVC preview

- contextual CAMERA request when required by the native pipeline
- official USB-device permission request
- automatic continuation after grants
- native libuvc MJPEG/YUY2 negotiation
- OpenGL/TextureView preview and fallback resolutions
- debug timing markers

Exit: technically complete preview path builds. Stop for a physical UVC test before feature expansion.

## 4. Reconnect, orientation, full screen

- repeated detach/attach hardening
- rotation/mirror transforms
- immersive landscape controls

## 5. Photo capture

- actual stream-frame capture
- JPEG publication to `Rammy AI Gun/Photos`

## 6. Video recording

- hardware H.264 + MP4
- video-only reliable baseline
- smooth concurrent preview

## 7. Gallery

- app-owned media query, grid, viewer/player, share, delete

## 8. Settings and transforms

- supported modes only, display modes, orientation, device information

## 9–10. Apple external camera

- validate AVFoundation external capture on supported iPad hardware
- connection lifecycle, photo/video, compatibility messaging

## 11. Optimization and compatibility

- first-frame profiling
- long-running and manufacturer matrix
- native dependency 16 KB page-size audit before Play release

