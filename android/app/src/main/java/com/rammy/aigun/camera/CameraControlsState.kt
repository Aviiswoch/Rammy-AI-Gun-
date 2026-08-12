package com.rammy.aigun.camera

enum class PreviewDisplayMode {
    Fit,
    Fill,
}

data class CameraTransformState(
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val displayMode: PreviewDisplayMode = PreviewDisplayMode.Fit,
)

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data class Recording(val startedAtElapsedMs: Long) : RecordingState
    data object Stopping : RecordingState
}

data class CameraControlsState(
    val transform: CameraTransformState = CameraTransformState(),
    val photoCaptureInProgress: Boolean = false,
    val recording: RecordingState = RecordingState.Idle,
)

sealed interface CameraUiEvent {
    data class Message(val text: String) : CameraUiEvent
    data object Shutter : CameraUiEvent
}
