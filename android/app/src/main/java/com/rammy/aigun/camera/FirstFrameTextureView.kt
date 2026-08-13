package com.rammy.aigun.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import com.serenegiant.widget.UVCCameraTextureView

class FirstFrameTextureView(context: Context) : UVCCameraTextureView(context) {
    var onFirstFrame: (() -> Unit)? = null
    var onFrameRendered: ((frameId: Long, elapsedNs: Long) -> Unit)? = null
    private var frameReported = false
    private var renderedFrameId = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var transformState = CameraTransformState()

    fun armFirstFrameCallback() {
        frameReported = false
        renderedFrameId = 0
    }

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        applyPreviewLayout()
    }

    fun setCameraTransform(transform: CameraTransformState) {
        transformState = transform
        applyPreviewLayout()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyPreviewLayout()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        super.onSurfaceTextureUpdated(surface)
        renderedFrameId++
        onFrameRendered?.invoke(renderedFrameId, SystemClock.elapsedRealtimeNanos())
        if (!frameReported) {
            frameReported = true
            onFirstFrame?.invoke()
        }
    }

    private fun applyPreviewLayout() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val quarterTurn = transformState.rotationDegrees == 90 || transformState.rotationDegrees == 270
        val displayWidth = if (quarterTurn) sourceHeight else sourceWidth
        val displayHeight = if (quarterTurn) sourceWidth else sourceHeight

        when (transformState.displayMode) {
            PreviewDisplayMode.Fit -> {
                setTransform(Matrix())
                setAspectRatio(displayWidth, displayHeight)
            }
            PreviewDisplayMode.Fill -> {
                setAspectRatio(0.0)
                post {
                    if (width <= 0 || height <= 0) return@post
                    val sourceAspect = displayWidth.toFloat() / displayHeight.toFloat()
                    val viewAspect = width.toFloat() / height.toFloat()
                    val scaleX: Float
                    val scaleY: Float
                    if (sourceAspect > viewAspect) {
                        scaleX = sourceAspect / viewAspect
                        scaleY = 1f
                    } else {
                        scaleX = 1f
                        scaleY = viewAspect / sourceAspect
                    }
                    setTransform(
                        Matrix().apply {
                            setScale(scaleX, scaleY, width / 2f, height / 2f)
                        },
                    )
                }
            }
        }
    }
}
