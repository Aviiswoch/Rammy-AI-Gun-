package com.rammy.aigun.camera

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = UsbCameraController(application)
    val state = controller.state

    fun attachPreviewView(view: FirstFrameTextureView) = controller.attachPreviewView(view)
    fun detachPreviewView(view: FirstFrameTextureView) = controller.detachPreviewView(view)
    fun allowAndConnect() = controller.allowAndConnect()
    fun retry() = controller.retry()
    fun rescan() = controller.rescan()

    override fun onCleared() {
        controller.destroy()
    }
}

