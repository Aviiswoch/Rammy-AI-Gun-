package com.rammy.aigun.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.rammy.aigun.BuildConfig
import com.serenegiant.usb.Size
import com.serenegiant.widget.CameraViewInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbCameraController(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbHostAvailable = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val mutableState = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Starting)
    val state: StateFlow<CameraConnectionState> = mutableState.asStateFlow()

    private var helper: CameraHelper? = null
    private var previewView: FirstFrameTextureView? = null
    private var currentSurface: Surface? = null
    private var currentDevice: UsbDevice? = null
    private var selectedSize: Size? = null
    private var fallbackSizes: List<Size> = emptyList()
    private var fallbackIndex = 0
    private var started = false

    private var usbDetectedAt = 0L
    private var permissionRequestedAt = 0L
    private var negotiationStartedAt = 0L
    private var cameraOpenedAt = 0L

    private val viewCallback = object : CameraViewInterface.Callback {
        override fun onSurfaceCreated(view: CameraViewInterface, surface: Surface) {
            currentSurface = surface
            if (helper?.isCameraOpened == true) helper?.addSurface(surface, false)
        }

        override fun onSurfaceChanged(view: CameraViewInterface, surface: Surface, width: Int, height: Int) = Unit

        override fun onSurfaceDestroy(view: CameraViewInterface, surface: Surface) {
            helper?.removeSurface(surface)
            if (currentSurface === surface) currentSurface = null
        }
    }

    private val cameraCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (!UvcClassifier.isUvc(device)) return
            usbDetectedAt = SystemClock.elapsedRealtime()
            metric("usb_detect_ms", 0)
            currentDevice = device
            val summary = device.toSummary()
            if (usbManager.hasPermission(device)) {
                permissionRequestedAt = usbDetectedAt
                mutableState.value = CameraConnectionState.Connecting(summary)
                helper?.selectDevice(device)
            } else {
                mutableState.value = CameraConnectionState.Detected(summary)
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (device.deviceId != currentDevice?.deviceId) return
            metric("permission_to_open_ms", elapsed(permissionRequestedAt))
            negotiationStartedAt = SystemClock.elapsedRealtime()
            fallbackSizes = rankSupportedSizes(helper?.supportedSizeList.orEmpty())
            fallbackIndex = 0
            openNextConfiguration()
        }

        override fun onCameraOpen(device: UsbDevice) {
            if (device.deviceId != currentDevice?.deviceId) return
            cameraOpenedAt = SystemClock.elapsedRealtime()
            metric("stream_negotiation_ms", elapsed(negotiationStartedAt))
            helper?.startPreview()
            previewView?.armFirstFrameCallback()
            currentSurface?.let { helper?.addSurface(it, false) }
            val size = helper?.previewSize ?: selectedSize
            size?.let { previewView?.setAspectRatio(it.width, it.height) }
            mutableState.value = CameraConnectionState.Streaming(
                ActiveStream(
                    device = device.toSummary(),
                    width = size?.width ?: 0,
                    height = size?.height ?: 0,
                    format = formatLabel(size),
                ),
            )
        }

        override fun onCameraClose(device: UsbDevice) {
            currentSurface?.let { helper?.removeSurface(it) }
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            if (device.deviceId != currentDevice?.deviceId) return
            closeCurrentCamera()
            currentDevice = null
            mutableState.value = CameraConnectionState.Disconnected
        }

        override fun onCancel(device: UsbDevice) {
            if (device.deviceId == currentDevice?.deviceId) {
                mutableState.value = CameraConnectionState.PermissionDenied(device.toSummary())
            }
        }

        override fun onError(device: UsbDevice, error: CameraException) {
            if (device.deviceId != currentDevice?.deviceId) return
            if (fallbackIndex < fallbackSizes.size) {
                openNextConfiguration()
            } else {
                mutableState.value = CameraConnectionState.Error(
                    message = if (error.code == CameraException.CAMERA_OPEN_ERROR_BUSY) {
                        "Camera is already being used by another app."
                    } else {
                        "Unable to start this camera stream. ${error.message.orEmpty()}".trim()
                    },
                )
            }
        }
    }

    fun attachPreviewView(view: FirstFrameTextureView) {
        if (previewView === view) return
        previewView?.setCallback(null)
        previewView = view.apply {
            setCallback(viewCallback)
            onFirstFrame = { metric("first_frame_ms", elapsed(cameraOpenedAt)) }
        }
        start()
    }

    fun detachPreviewView(view: FirstFrameTextureView) {
        if (previewView !== view) return
        currentSurface?.let { helper?.removeSurface(it) }
        currentSurface = null
        view.onFirstFrame = null
        view.setCallback(null)
        previewView = null
    }

    fun allowAndConnect() {
        val device = currentDevice ?: return
        permissionRequestedAt = SystemClock.elapsedRealtime()
        mutableState.value = CameraConnectionState.Connecting(device.toSummary())
        helper?.selectDevice(device)
    }

    fun retry() {
        val device = currentDevice
        if (device != null) {
            if (usbManager.hasPermission(device)) {
                mutableState.value = CameraConnectionState.Connecting(device.toSummary())
                helper?.selectDevice(device)
            } else {
                mutableState.value = CameraConnectionState.Detected(device.toSummary())
            }
        } else {
            rescan()
        }
    }

    fun rescan() {
        if (!started) return
        val candidate = helper?.deviceList?.firstOrNull(UvcClassifier::isUvc)
        if (candidate != null) cameraCallback.onAttach(candidate)
        else if (currentDevice == null) mutableState.value = CameraConnectionState.Waiting
    }

    fun destroy() {
        closeCurrentCamera()
        helper?.setStateCallback(null)
        helper?.releaseAll()
        helper = null
        started = false
    }

    private fun start() {
        if (started) return
        if (!usbHostAvailable) {
            mutableState.value = CameraConnectionState.UsbHostUnavailable
            return
        }
        started = true
        helper = CameraHelper().also { it.setStateCallback(cameraCallback) }
        previewView?.post(::rescan)
    }

    private fun openNextConfiguration() {
        val cameraHelper = helper ?: return
        val next = fallbackSizes.getOrNull(fallbackIndex++)
        selectedSize = next
        if (next == null) cameraHelper.openCamera() else cameraHelper.openCamera(next)
    }

    private fun closeCurrentCamera() {
        currentSurface?.let { helper?.removeSurface(it) }
        runCatching { helper?.stopPreview() }
        runCatching { helper?.closeCamera() }
        fallbackSizes = emptyList()
        fallbackIndex = 0
    }

    private fun rankSupportedSizes(sizes: List<Size>): List<Size> {
        if (sizes.isEmpty()) return emptyList()
        val targets = listOf(1920 to 1080, 1280 to 720, 640 to 480)
        val ranked = targets.flatMap { (width, height) ->
            sizes.filter { it.width == width && it.height == height }
                .sortedWith(compareByDescending<Size> { it.type }.thenByDescending { it.fps })
        }
        val safeExtras = sizes
            .filter { it.width <= 1920 && it.height <= 1080 && it !in ranked }
            .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.fps })
        return (ranked + safeExtras).distinctBy { listOf(it.type, it.width, it.height, it.fps) }
    }

    private fun formatLabel(size: Size?): String = when (size?.type) {
        4, 6 -> "MJPEG"
        2, 3 -> "YUY2"
        else -> "UVC"
    }

    private fun UsbDevice.toSummary() = UsbCameraDevice(
        id = deviceId,
        name = productName?.takeIf(String::isNotBlank) ?: "USB Camera",
        vendorId = vendorId,
        productId = productId,
    )

    private fun elapsed(start: Long): Long =
        if (start == 0L) 0L else SystemClock.elapsedRealtime() - start

    private fun metric(name: String, value: Long) {
        if (BuildConfig.DEBUG) Log.d(METRICS_TAG, "$name=$value")
    }

    private companion object {
        const val METRICS_TAG = "RammyCameraMetrics"
    }
}

