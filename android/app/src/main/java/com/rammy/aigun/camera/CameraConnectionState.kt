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
    data class UsbPermissionDenied(val device: UsbCameraDevice) : CameraConnectionState
    data class NoDeviceEnumerated(val connectedDeviceCount: Int) : CameraConnectionState
    data class Error(
        val stage: String,
        val message: String,
        val retryable: Boolean = true,
    ) : CameraConnectionState
}

data class UsbCameraDevice(
    val id: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val interfaces: List<String>,
    val selectedUvcInterface: Int?,
)

data class ActiveStream(
    val device: UsbCameraDevice,
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: String,
)

data class UsbDiagnostics(
    val hostSupported: Boolean = false,
    val connectedDeviceCount: Int = 0,
    val cameraDetected: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val deviceClass: Int? = null,
    val interfaces: List<String> = emptyList(),
    val selectedUvcInterface: Int? = null,
    val deviceOpened: Boolean = false,
    val resolution: String = "Not negotiated",
    val fps: String = "Not negotiated",
    val format: String = "Not negotiated",
    val firstFrameReceived: Boolean = false,
    val lastFailureStage: String? = null,
    val artifact: UvcArtifactDiagnostics = UvcArtifactDiagnostics(),
) {
    fun asText(): String = buildString {
        appendLine("Rammy AI Gun - USB diagnostics")
        appendLine("USB Host supported: ${yesNo(hostSupported)}")
        appendLine("Connected USB devices: $connectedDeviceCount")
        appendLine("Camera detected: ${yesNo(cameraDetected)}")
        appendLine("USB permission: ${granted(usbPermissionGranted)}")
        appendLine("Camera permission: ${granted(cameraPermissionGranted)}")
        appendLine("Vendor ID: ${vendorId ?: "N/A"}")
        appendLine("Product ID: ${productId ?: "N/A"}")
        appendLine("Device class: ${deviceClass ?: "N/A"}")
        appendLine("Interfaces: ${if (interfaces.isEmpty()) "None" else interfaces.joinToString("; ")}")
        appendLine("Selected UVC interface: ${selectedUvcInterface ?: "None"}")
        appendLine("Device opened: ${yesNo(deviceOpened)}")
        appendLine("Resolution: $resolution")
        appendLine("FPS: $fps")
        appendLine("Format: $format")
        appendLine("First frame: ${yesNo(firstFrameReceived)}")
        appendLine("Last failure stage: ${lastFailureStage ?: "None"}")
        appendLine()
        append(artifact.asText())
    }

    private fun yesNo(value: Boolean) = if (value) "YES" else "NO"
    private fun granted(value: Boolean) = if (value) "GRANTED" else "NOT GRANTED"
}
