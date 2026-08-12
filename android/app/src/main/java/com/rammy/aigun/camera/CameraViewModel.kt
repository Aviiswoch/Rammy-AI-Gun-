package com.rammy.aigun.camera

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = UsbCameraController(application)
    val state = controller.state
    val diagnostics = controller.diagnostics
    val controls = controller.controls
    val events = controller.events

    fun attachPreviewView(view: FirstFrameTextureView) = controller.attachPreviewView(view)
    fun detachPreviewView(view: FirstFrameTextureView) = controller.detachPreviewView(view)
    fun allowAndConnect() = controller.allowAndConnect()
    fun onCameraPermissionResult(granted: Boolean, permanentlyDenied: Boolean) =
        controller.onCameraPermissionResult(granted, permanentlyDenied)
    fun retry() = controller.retry()
    fun rescan() = controller.rescan()
    fun takePhoto() = controller.takePhoto()
    fun toggleRecording() = controller.toggleRecording()
    fun rotatePreviewClockwise() = controller.rotatePreviewClockwise()
    fun toggleDisplayMode() = controller.toggleDisplayMode()
    fun onAppBackgrounded() = controller.onAppBackgrounded()

    override fun onCleared() {
        controller.destroy()
    }
}
