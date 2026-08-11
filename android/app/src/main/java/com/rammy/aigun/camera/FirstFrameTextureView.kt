package com.rammy.aigun.camera

import android.content.Context
import android.graphics.SurfaceTexture
import com.serenegiant.widget.UVCCameraTextureView

class FirstFrameTextureView(context: Context) : UVCCameraTextureView(context) {
    var onFirstFrame: (() -> Unit)? = null
    private var frameReported = false

    fun armFirstFrameCallback() {
        frameReported = false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        super.onSurfaceTextureUpdated(surface)
        if (!frameReported) {
            frameReported = true
            onFirstFrame?.invoke()
        }
    }
}

