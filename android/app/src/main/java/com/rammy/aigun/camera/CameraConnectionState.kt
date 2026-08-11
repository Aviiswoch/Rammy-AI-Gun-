package com.rammy.aigun.camera

sealed interface CameraConnectionState {
    data object Starting : CameraConnectionState
    data object UsbHostUnavailable : CameraConnectionState
    data object Waiting : CameraConnectionState
    data class Detected(val device: UsbCameraDevice) : CameraConnectionState
    data class Connecting(val device: UsbCameraDevice) : CameraConnectionState
    data class Streaming(val stream: ActiveStream) : CameraConnectionState
    data object Disconnected : CameraConnectionState
    data class PermissionDenied(val device: UsbCameraDevice) : CameraConnectionState
    data class Error(val message: String, val retryable: Boolean = true) : CameraConnectionState
}

data class UsbCameraDevice(
    val id: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
)

data class ActiveStream(
    val device: UsbCameraDevice,
    val width: Int,
    val height: Int,
    val format: String = "MJPEG / YUY2 fallback",
)

