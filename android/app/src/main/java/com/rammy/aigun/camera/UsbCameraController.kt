package com.rammy.aigun.camera

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
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
    private val usbHostSupported =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val mutableState = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Starting)
    val state: StateFlow<CameraConnectionState> = mutableState.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(
        UsbDiagnostics(
            hostSupported = usbHostSupported,
            cameraPermissionGranted = hasCameraPermission(),
        ),
    )
    val diagnostics: StateFlow<UsbDiagnostics> = mutableDiagnostics.asStateFlow()

    private var helper: CameraHelper? = null
    private var previewView: FirstFrameTextureView? = null
    private var currentSurface: Surface? = null
    private var currentDevice: UsbDevice? = null
    private var selectedSize: Size? = null
    private var fallbackSizes: List<Size> = emptyList()
    private var fallbackIndex = 0
    private var started = false
    private var receiverRegistered = false
    private var usbPermissionRequestInFlight = false
    private var connectionStartedAt = 0L

    private val permissionIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(
            appContext,
            USB_PERMISSION_REQUEST_CODE,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags,
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handleUsbPermissionResult(intent.usbDevice(), intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    debug("[USB] Attach broadcast device=${device?.deviceName ?: "unknown"}")
                    if (device != null) {
                        logDevice(device)
                        if (UvcClassifier.isUvc(device)) handleEnumeratedCamera(device)
                        else rescan(userInitiated = true)
                    } else {
                        rescan()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> intent.usbDevice()?.let(::handleDetach)
            }
        }
    }

    private val viewCallback = object : CameraViewInterface.Callback {
        override fun onSurfaceCreated(view: CameraViewInterface, surface: Surface) {
            currentSurface = surface
            if (helper?.isCameraOpened == true) helper?.addSurface(surface, false)
        }

        override fun onSurfaceChanged(
            view: CameraViewInterface,
            surface: Surface,
            width: Int,
            height: Int,
        ) = Unit

        override fun onSurfaceDestroy(view: CameraViewInterface, surface: Surface) {
            helper?.removeSurface(surface)
            if (currentSurface === surface) currentSurface = null
        }
    }

    private val cameraCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            // CameraHelper also monitors the bus. Native enumeration remains the source of truth,
            // while this callback covers an OEM attach broadcast that our process did not receive.
            if (UvcClassifier.isUvc(device)) handleEnumeratedCamera(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (!isCurrent(device)) return
            debug("[USB] UVC library device open=success")
            debug("[UVC] Negotiating stream")
            mutableDiagnostics.value = mutableDiagnostics.value.copy(deviceOpened = true)
            metric("deviceOpenedMs", connectionElapsed())
            mutableState.value = CameraConnectionState.NegotiatingStream(device.toSummary())
            fallbackSizes = rankSupportedSizes(helper?.supportedSizeList.orEmpty())
            fallbackIndex = 0
            openNextConfiguration()
        }

        override fun onCameraOpen(device: UsbDevice) {
            if (!isCurrent(device)) return
            val size = helper?.previewSize ?: selectedSize
            debug(
                "[UVC] Stream negotiated ${size?.width ?: 0}x${size?.height ?: 0} " +
                    "${formatLabel(size)} ${size?.fps ?: 0}fps",
            )
            metric("streamNegotiatedMs", connectionElapsed())
            val previewStarted = runCatching {
                helper?.startPreview()
                previewView?.armFirstFrameCallback()
                currentSurface?.let { helper?.addSurface(it, false) }
            }.onFailure {
                fail("UVC_PREVIEW_START_FAILED", it.message ?: "The UVC preview could not start.")
            }.isSuccess
            if (!previewStarted) return
            size?.let { previewView?.setAspectRatio(it.width, it.height) }

            val stream = ActiveStream(
                device = device.toSummary(),
                width = size?.width ?: 0,
                height = size?.height ?: 0,
                fps = size?.fps ?: 0,
                format = formatLabel(size),
            )
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                resolution = "${stream.width}x${stream.height}",
                fps = stream.fps.toString(),
                format = stream.format,
                lastFailureStage = null,
            )
            mutableState.value = CameraConnectionState.Streaming(stream)
        }

        override fun onCameraClose(device: UsbDevice) {
            currentSurface?.let { helper?.removeSurface(it) }
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) = handleDetach(device)

        override fun onCancel(device: UsbDevice) {
            if (!isCurrent(device)) return
            usbPermissionRequestInFlight = false
            if (usbManager.hasPermission(device)) {
                fail(
                    stage = "UVC_LIBRARY_OPEN_CANCELLED",
                    message = "USB access was granted, but the UVC camera could not be opened.",
                )
            } else {
                debug("[USB] Permission granted=false (library callback)")
                mutableState.value = CameraConnectionState.UsbPermissionDenied(device.toSummary())
            }
        }

        override fun onError(device: UsbDevice, error: CameraException) {
            if (!isCurrent(device)) return
            if (fallbackIndex < fallbackSizes.size) {
                debug("[UVC] Configuration failed; trying next supported mode")
                runCatching { helper?.closeCamera() }
                previewView?.postDelayed(::openNextConfiguration, FALLBACK_RETRY_DELAY_MS)
                return
            }

            val message = if (error.code == CameraException.CAMERA_OPEN_ERROR_BUSY) {
                "Camera is already being used by another app."
            } else {
                "Unable to start a supported UVC stream. ${error.message.orEmpty()}".trim()
            }
            fail("UVC_STREAM_FAILED", message)
        }
    }

    fun attachPreviewView(view: FirstFrameTextureView) {
        if (previewView === view) return
        previewView?.setCallback(null)
        previewView = view.apply {
            setCallback(viewCallback)
            onFirstFrame = {
                debug("[UVC] First frame received")
                metric("firstFrameMs", connectionElapsed())
                mutableDiagnostics.value = mutableDiagnostics.value.copy(firstFrameReceived = true)
            }
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

    /** Called after the in-app CAMERA explanation, or to retry USB permission. */
    fun allowAndConnect() {
        val device = currentDevice ?: run {
            rescan(userInitiated = true)
            return
        }
        if (!hasCameraPermission()) {
            mutableState.value = CameraConnectionState.CameraPermissionRequired(device.toSummary())
            return
        }
        continueAfterCameraPermission(device, force = true)
    }

    fun onCameraPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(cameraPermissionGranted = granted)
        val device = currentDevice ?: run {
            rescan()
            return
        }
        if (granted) {
            debug("[USB] Camera runtime permission=granted")
            continueAfterCameraPermission(device, force = true)
        } else {
            debug("[USB] Camera runtime permission=denied")
            mutableState.value = CameraConnectionState.CameraPermissionDenied(
                device = device.toSummary(),
                permanentlyDenied = permanentlyDenied,
            )
        }
    }

    fun retry() {
        val device = currentDevice
        if (device == null) rescan(userInitiated = true)
        else handleEnumeratedCamera(device, force = true)
    }

    fun rescan(userInitiated: Boolean = false) {
        if (!started) return
        val devices = usbManager.deviceList.values.toList()
        debug("[USB] Devices found=${devices.size}")
        devices.forEach(::logDevice)

        val candidate = devices.firstOrNull(UvcClassifier::isUvc)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            connectedDeviceCount = devices.size,
            cameraDetected = candidate != null,
            cameraPermissionGranted = hasCameraPermission(),
        )

        if (candidate != null) {
            handleEnumeratedCamera(candidate, force = userInitiated)
        } else {
            if (currentDevice != null) handleDetach(currentDevice!!)
            mutableState.value = if (userInitiated) {
                CameraConnectionState.NoDeviceEnumerated(devices.size)
            } else {
                CameraConnectionState.WaitingForDevice
            }
        }
    }

    fun destroy() {
        closeCurrentCamera()
        helper?.setStateCallback(null)
        helper?.releaseAll()
        helper = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        started = false
    }

    private fun start() {
        if (started) return
        debug("[USB] Host supported=$usbHostSupported")
        debug("USB_HOST_SUPPORTED=$usbHostSupported")
        if (!usbHostSupported) {
            mutableState.value = CameraConnectionState.UsbHostUnavailable
            return
        }

        started = true
        registerUsbReceiver()
        helper = CameraHelper().also { it.setStateCallback(cameraCallback) }
        previewView?.post(::rescan)
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun handleEnumeratedCamera(device: UsbDevice, force: Boolean = false) {
        if (!UvcClassifier.isUvc(device)) return
        val existingState = mutableState.value
        if (device.deviceId == currentDevice?.deviceId && !force) {
            if (
                existingState is CameraConnectionState.OpeningDevice ||
                existingState is CameraConnectionState.NegotiatingStream ||
                existingState is CameraConnectionState.Streaming ||
                existingState is CameraConnectionState.UsbPermissionRequired ||
                (existingState is CameraConnectionState.UsbPermissionDenied &&
                    !usbManager.hasPermission(device)) ||
                (existingState is CameraConnectionState.CameraPermissionDenied &&
                    !hasCameraPermission()) ||
                existingState is CameraConnectionState.Error
            ) return
        }

        if (device.deviceId != currentDevice?.deviceId) {
            closeCurrentCamera()
            currentDevice = device
            connectionStartedAt = SystemClock.elapsedRealtime()
            metric("attachDetectedMs", 0)
        }

        val summary = device.toSummary()
        val hasUsbPermission = usbManager.hasPermission(device)
        debug("[USB] UVC camera detected=true")
        debug("[USB] Permission=$hasUsbPermission")
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            connectedDeviceCount = usbManager.deviceList.size,
            cameraDetected = true,
            usbPermissionGranted = hasUsbPermission,
            cameraPermissionGranted = hasCameraPermission(),
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            interfaces = summary.interfaces,
            selectedUvcInterface = summary.selectedUvcInterface,
            deviceOpened = false,
            firstFrameReceived = false,
            resolution = "Not negotiated",
            fps = "Not negotiated",
            format = "Not negotiated",
            lastFailureStage = null,
        )
        mutableState.value = CameraConnectionState.DeviceDetected(summary)

        if (!hasCameraPermission()) {
            mutableState.value = CameraConnectionState.CameraPermissionRequired(summary)
            return
        }
        continueAfterCameraPermission(device, force)
    }

    private fun continueAfterCameraPermission(device: UsbDevice, force: Boolean) {
        if (!isCurrent(device)) return
        mutableDiagnostics.value = mutableDiagnostics.value.copy(cameraPermissionGranted = true)
        if (usbManager.hasPermission(device)) {
            openAuthorizedDevice(device)
        } else if (force || !usbPermissionRequestInFlight) {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbPermissionRequestInFlight || !isCurrent(device)) return
        usbPermissionRequestInFlight = true
        mutableState.value = CameraConnectionState.UsbPermissionRequired(device.toSummary())
        debug("[USB] Requesting permission")
        metric("permissionRequestMs", connectionElapsed())
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (error: RuntimeException) {
            usbPermissionRequestInFlight = false
            fail("USB_PERMISSION_REQUEST_FAILED", error.message ?: "USB permission request failed.")
        }
    }

    private fun handleUsbPermissionResult(deviceFromIntent: UsbDevice?, intent: Intent) {
        usbPermissionRequestInFlight = false
        val device = deviceFromIntent ?: currentDevice ?: return
        if (!isCurrent(device)) return
        val grantedByIntent = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val granted = grantedByIntent && usbManager.hasPermission(device)
        debug("[USB] Permission granted=$granted")
        mutableDiagnostics.value = mutableDiagnostics.value.copy(usbPermissionGranted = granted)
        if (granted) {
            metric("permissionGrantedMs", connectionElapsed())
            openAuthorizedDevice(device)
        } else {
            mutableState.value = CameraConnectionState.UsbPermissionDenied(device.toSummary())
        }
    }

    private fun openAuthorizedDevice(device: UsbDevice) {
        if (!isCurrent(device)) return
        mutableState.value = CameraConnectionState.OpeningDevice(device.toSummary())
        debug("[USB] Opening device")

        // Verify the platform open step explicitly, then release this probe connection. CameraHelper
        // opens its own UsbDeviceConnection for the native libuvc pipeline immediately afterwards.
        val probeConnection = runCatching { usbManager.openDevice(device) }.getOrNull()
        if (probeConnection == null) {
            debug("[USB] openDevice=failed")
            fail("USB_OPEN_FAILED", "Android granted USB access, but openDevice() returned null.")
            return
        }
        debug("[USB] openDevice=success")
        runCatching { probeConnection.close() }
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            usbPermissionGranted = true,
            deviceOpened = true,
        )
        metric("deviceOpenedMs", connectionElapsed())
        val cameraHelper = helper
        if (cameraHelper == null) {
            fail("UVC_HELPER_UNAVAILABLE", "The UVC camera pipeline is not available.")
            return
        }
        runCatching { cameraHelper.selectDevice(device) }
            .onFailure {
                fail("UVC_DEVICE_SELECTION_FAILED", it.message ?: "The UVC device could not be selected.")
            }
    }

    private fun handleDetach(device: UsbDevice) {
        if (!isCurrent(device)) return
        debug("[USB] Camera detached VID=${device.vendorId} PID=${device.productId}")
        closeCurrentCamera()
        currentDevice = null
        usbPermissionRequestInFlight = false
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            connectedDeviceCount = usbManager.deviceList.size,
            cameraDetected = false,
            usbPermissionGranted = false,
            deviceOpened = false,
            firstFrameReceived = false,
            resolution = "Not negotiated",
            fps = "Not negotiated",
            format = "Not negotiated",
        )
        mutableState.value = CameraConnectionState.Disconnected
    }

    private fun openNextConfiguration() {
        val cameraHelper = helper ?: return
        val next = fallbackSizes.getOrNull(fallbackIndex++)
        selectedSize = next
        if (next == null) {
            debug("[UVC] Negotiating default stream")
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                resolution = "Library default",
                fps = "Library default",
                format = "Library default",
            )
            cameraHelper.openCamera()
        } else {
            debug("[UVC] Trying ${next.width}x${next.height} ${formatLabel(next)} ${next.fps}fps")
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                resolution = "${next.width}x${next.height}",
                fps = next.fps.toString(),
                format = formatLabel(next),
            )
            cameraHelper.openCamera(next)
        }
    }

    private fun closeCurrentCamera() {
        currentSurface?.let { helper?.removeSurface(it) }
        runCatching { helper?.stopPreview() }
        runCatching { helper?.closeCamera() }
        fallbackSizes = emptyList()
        fallbackIndex = 0
        selectedSize = null
    }

    private fun rankSupportedSizes(sizes: List<Size>): List<Size> {
        if (sizes.isEmpty()) return emptyList()
        val targets = listOf(1920 to 1080, 1280 to 720, 640 to 480)
        val targetSizes = targets.flatMap { (width, height) ->
            sizes.filter { it.width == width && it.height == height }
                .sortedByDescending { it.fps }
        }
        val ranked = targetSizes.sortedWith(
            compareByDescending<Size> { formatPriority(it.type) }
                .thenBy { targets.indexOf(it.width to it.height) }
                .thenByDescending { it.fps },
        )
        val safeExtras = sizes
            .filter { it.width <= 1920 && it.height <= 1080 && it !in ranked }
            .sortedWith(
                compareByDescending<Size> { formatPriority(it.type) }
                    .thenByDescending { it.width * it.height }
                    .thenByDescending { it.fps },
            )
        return (ranked + safeExtras).distinctBy { listOf(it.type, it.width, it.height, it.fps) }
    }

    private fun formatPriority(type: Int): Int = when (type) {
        UVC_FORMAT_MJPEG, UVC_FRAME_MJPEG -> 2
        UVC_FORMAT_UNCOMPRESSED, UVC_FRAME_UNCOMPRESSED -> 1
        else -> 0
    }

    private fun formatLabel(size: Size?): String = when (size?.type) {
        UVC_FORMAT_MJPEG, UVC_FRAME_MJPEG -> "MJPEG"
        UVC_FORMAT_UNCOMPRESSED, UVC_FRAME_UNCOMPRESSED -> "YUY2/YUV"
        else -> "UVC"
    }

    private fun logDevice(device: UsbDevice) {
        debug(
            "[USB] Device name=${device.deviceName} VID=${device.vendorId} PID=${device.productId} " +
                "class=${device.deviceClass} subclass=${device.deviceSubclass} " +
                "protocol=${device.deviceProtocol} interfaces=${device.interfaceCount}",
        )
        repeat(device.interfaceCount) { index ->
            val usbInterface = device.getInterface(index)
            debug(
                "[USB] Interface $index class=${usbInterface.interfaceClass} " +
                    "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                    "endpoints=${usbInterface.endpointCount}",
            )
        }
    }

    private fun UsbDevice.toSummary(): UsbCameraDevice {
        val interfaceDescriptions = List(interfaceCount) { index ->
            val item = getInterface(index)
            "$index: class=${item.interfaceClass}, subclass=${item.interfaceSubclass}, " +
                "protocol=${item.interfaceProtocol}, endpoints=${item.endpointCount}"
        }
        return UsbCameraDevice(
            id = deviceId,
            name = runCatching { productName }.getOrNull()?.takeIf(String::isNotBlank) ?: "USB Camera",
            vendorId = vendorId,
            productId = productId,
            deviceClass = deviceClass,
            interfaces = interfaceDescriptions,
            selectedUvcInterface = UvcClassifier.videoInterfaceIndices(this).firstOrNull(),
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCurrent(device: UsbDevice): Boolean = device.deviceId == currentDevice?.deviceId

    private fun fail(stage: String, message: String, retryable: Boolean = true) {
        debug("[USB] $stage: $message")
        mutableDiagnostics.value = mutableDiagnostics.value.copy(lastFailureStage = stage)
        mutableState.value = CameraConnectionState.Error(stage, message, retryable)
    }

    private fun connectionElapsed(): Long =
        if (connectionStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - connectionStartedAt

    private fun metric(name: String, value: Long) {
        if (BuildConfig.DEBUG) Log.d(METRICS_TAG, "$name=$value")
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(USB_TAG, message)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.rammy.aigun.USB_PERMISSION"
        const val USB_PERMISSION_REQUEST_CODE = 4107
        const val FALLBACK_RETRY_DELAY_MS = 120L
        const val USB_TAG = "RammyUsb"
        const val METRICS_TAG = "RammyCameraMetrics"

        const val UVC_FORMAT_UNCOMPRESSED = 4
        const val UVC_FRAME_UNCOMPRESSED = 5
        const val UVC_FORMAT_MJPEG = 6
        const val UVC_FRAME_MJPEG = 7
    }
}
