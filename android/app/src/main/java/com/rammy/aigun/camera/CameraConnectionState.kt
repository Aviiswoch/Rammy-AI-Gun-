package com.rammy.aigun.camera

sealed interface CameraConnectionState {
    data object Starting : CameraConnectionState
    data object UsbHostUnavailable : CameraConnectionState
    data object WaitingForDevice : CameraConnectionState
    data class DeviceDetected(val device: UsbCameraDevice) : CameraConnectionState
    data class CameraPermissionRequired(val device: UsbCameraDevice) : CameraConnectionState
    data class CameraPermissionDenied(
        val device: UsbCameraDevice,
        val permanentlyDenied: Boolean,
    ) : CameraConnectionState
    data class UsbPermissionRequired(val device: UsbCameraDevice) : CameraConnectionState
    data class OpeningDevice(val device: UsbCameraDevice) : CameraConnectionState
    data class NegotiatingStream(val device: UsbCameraDevice) : CameraConnectionState
    data class Streaming(val stream: ActiveStream) : CameraConnectionState
    data object Disconnected : CameraConnectionState
    data class UsbPermissionDenied(
        val device: UsbCameraDevice,
        val suggestSettings: Boolean = false,
    ) : CameraConnectionState
    data class NoDeviceEnumerated(val connectedDeviceCount: Int) : CameraConnectionState
    data class Error(
        val stage: String,
        val message: String,
        val retryable: Boolean = true,
    ) : CameraConnectionState
}
