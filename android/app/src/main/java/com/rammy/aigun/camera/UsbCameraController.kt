package com.rammy.aigun.camera

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.herohan.uvcapp.VideoCapture
import com.rammy.aigun.BuildConfig
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.CameraViewInterface
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    private val mutableControls = MutableStateFlow(CameraControlsState())
    val controls: StateFlow<CameraControlsState> = mutableControls.asStateFlow()

    private val mutableEvents = MutableSharedFlow<CameraUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CameraUiEvent> = mutableEvents.asSharedFlow()

    private val mediaStore = MediaStorePublisher(appContext)
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val photoCaptureGuard = AtomicBoolean(false)
    private val mediaSessionLock = Any()
    private val artifactMonitor = UvcArtifactMonitor(
        enabled = BuildConfig.DEBUG,
        logger = ::artifactLog,
    )
    private val artifactPublishRunnable = object : Runnable {
        override fun run() {
            if (!artifactMonitor.enabled) return
            publishArtifactDiagnostics()
            mainHandler.postDelayed(this, ARTIFACT_SNAPSHOT_INTERVAL_MS)
        }
    }

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
    private var activePhoto: ActivePhoto? = null
    private var activeRecording: ActiveRecording? = null

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
            size?.let { previewView?.setSourceSize(it.width, it.height) }
            configureMediaCapture(size)
            applyRendererTransform()
            previewView?.setCameraTransform(mutableControls.value.transform)
            startArtifactDiagnostics(device, size)

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
            setCameraTransform(mutableControls.value.transform)
            selectedSize?.let { setSourceSize(it.width, it.height) }
            onFirstFrame = {
                debug("[UVC] First frame received")
                metric("firstFrameMs", connectionElapsed())
                mutableDiagnostics.value = mutableDiagnostics.value.copy(firstFrameReceived = true)
            }
            onFrameRendered = if (artifactMonitor.enabled) artifactMonitor::onRendered else null
        }
        start()
    }

    fun detachPreviewView(view: FirstFrameTextureView) {
        if (previewView !== view) return
        currentSurface?.let { helper?.removeSurface(it) }
        currentSurface = null
        view.onFirstFrame = null
        view.onFrameRendered = null
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

    fun takePhoto() {
        if (mutableState.value !is CameraConnectionState.Streaming || helper?.isCameraOpened != true) {
            mutableEvents.tryEmit(CameraUiEvent.Message("Camera is not ready"))
            return
        }
        if (!photoCaptureGuard.compareAndSet(false, true)) return

        mutableControls.value = mutableControls.value.copy(photoCaptureInProgress = true)
        mutableEvents.tryEmit(CameraUiEvent.Shutter)
        mediaExecutor.execute {
            var pending: MediaStorePublisher.PendingMedia? = null
            var output: OutputStream? = null
            try {
                pending = mediaStore.createPhoto()
                output = mediaStore.resolver.openOutputStream(pending.uri, "w")
                    ?: error("Unable to open the Gallery photo output")
                val session = ActivePhoto(pending, output)
                synchronized(mediaSessionLock) { activePhoto = session }
                val options = IImageCapture.OutputFileOptions.Builder(output).build()
                helper?.takePicture(
                    options,
                    object : IImageCapture.OnImageCaptureCallback {
                        override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) {
                            completePhoto(session, success = true, errorMessage = null)
                        }

                        override fun onError(
                            imageCaptureError: Int,
                            message: String,
                            cause: Throwable?,
                        ) {
                            completePhoto(
                                session,
                                success = false,
                                errorMessage = message.ifBlank { "Photo could not be saved" },
                            )
                        }
                    },
                ) ?: error("The UVC image-capture pipeline is unavailable")
            } catch (error: Exception) {
                runCatching { output?.close() }
                pending?.let(mediaStore::discard)
                synchronized(mediaSessionLock) { activePhoto = null }
                finishPhotoState(error.message ?: "Photo could not be saved")
            }
        }
    }

    fun toggleRecording() {
        when (mutableControls.value.recording) {
            RecordingState.Idle -> startRecording()
            is RecordingState.Recording -> stopRecordingSafely("User stopped recording")
            RecordingState.Starting,
            RecordingState.Stopping,
            -> Unit
        }
    }

    fun rotatePreviewClockwise() {
        if (mutableState.value !is CameraConnectionState.Streaming) return
        val current = mutableControls.value
        val nextRotation = (current.transform.rotationDegrees + 90) % 360
        mutableControls.value = current.copy(
            transform = current.transform.copy(rotationDegrees = nextRotation),
        )
        applyRendererTransform()
        previewView?.setCameraTransform(mutableControls.value.transform)
        mutableEvents.tryEmit(
            CameraUiEvent.Message(if (nextRotation == 0) "Normal" else "$nextRotation°"),
        )
    }

    fun toggleDisplayMode() {
        if (mutableState.value !is CameraConnectionState.Streaming) return
        val current = mutableControls.value
        val next = if (current.transform.displayMode == PreviewDisplayMode.Fit) {
            PreviewDisplayMode.Fill
        } else {
            PreviewDisplayMode.Fit
        }
        mutableControls.value = current.copy(transform = current.transform.copy(displayMode = next))
        previewView?.setCameraTransform(mutableControls.value.transform)
        mutableEvents.tryEmit(CameraUiEvent.Message(if (next == PreviewDisplayMode.Fit) "Fit" else "Fill"))
    }

    fun onAppBackgrounded() {
        stopRecordingSafely("App moved to background")
    }

    fun destroy() {
        stopRecordingSafely("App closed")
        cancelActivePhoto()
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

    private fun completePhoto(session: ActivePhoto, success: Boolean, errorMessage: String?) {
        mediaExecutor.execute {
            val ownsSession = synchronized(mediaSessionLock) {
                if (activePhoto !== session) false else {
                    activePhoto = null
                    true
                }
            }
            if (!ownsSession) return@execute
            runCatching { session.output.close() }
            if (success) {
                runCatching { mediaStore.publish(session.media) }
                    .onSuccess { finishPhotoState(null) }
                    .onFailure {
                        mediaStore.discard(session.media)
                        finishPhotoState(it.message ?: "Photo could not be published to Gallery")
                    }
            } else {
                mediaStore.discard(session.media)
                finishPhotoState(errorMessage ?: "Photo could not be saved")
            }
        }
    }

    private fun finishPhotoState(errorMessage: String?) {
        photoCaptureGuard.set(false)
        mutableControls.value = mutableControls.value.copy(photoCaptureInProgress = false)
        mutableEvents.tryEmit(
            CameraUiEvent.Message(errorMessage ?: "Photo saved"),
        )
    }

    private fun cancelActivePhoto() {
        val session = synchronized(mediaSessionLock) {
            activePhoto.also { activePhoto = null }
        } ?: return
        mediaExecutor.execute {
            runCatching { session.output.close() }
            mediaStore.discard(session.media)
            finishPhotoState("Photo capture interrupted")
        }
    }

    private fun startRecording() {
        val stream = (mutableState.value as? CameraConnectionState.Streaming)?.stream
        if (stream == null || helper?.isCameraOpened != true) {
            mutableEvents.tryEmit(CameraUiEvent.Message("Camera is not ready"))
            return
        }
        if (mutableControls.value.recording != RecordingState.Idle) return
        mutableControls.value = mutableControls.value.copy(recording = RecordingState.Starting)

        mediaExecutor.execute {
            if (mutableControls.value.recording != RecordingState.Starting) return@execute
            var pending: MediaStorePublisher.PendingMedia? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                pending = mediaStore.createVideo()
                descriptor = mediaStore.resolver.openFileDescriptor(pending.uri, "rw")
                    ?: error("Unable to open the Gallery video output")
                if (mutableControls.value.recording != RecordingState.Starting) {
                    descriptor.close()
                    mediaStore.discard(pending)
                    return@execute
                }
                val session = ActiveRecording(pending, descriptor)
                synchronized(mediaSessionLock) { activeRecording = session }
                configureVideoCapture(stream)
                val options = VideoCapture.OutputFileOptions.Builder(descriptor.fileDescriptor).build()
                val cameraHelper = helper ?: error("The UVC recording pipeline is unavailable")
                cameraHelper.startRecording(
                    options,
                    object : VideoCapture.OnVideoCaptureCallback {
                        override fun onStart() {
                            if (!isActiveRecording(session)) return
                            session.started.set(true)
                            if (mutableControls.value.recording == RecordingState.Stopping) {
                                helper?.stopRecording()
                                return
                            }
                            mutableControls.value = mutableControls.value.copy(
                                recording = RecordingState.Recording(SystemClock.elapsedRealtime()),
                            )
                        }

                        override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                            finishRecording(session, publish = true, message = "Video saved")
                        }

                        override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                            finishRecording(
                                session,
                                publish = false,
                                message = message.ifBlank { "Video recording failed" },
                            )
                        }
                    },
                )
                mainHandler.postDelayed(
                    {
                        if (
                            isActiveRecording(session) &&
                            mutableControls.value.recording == RecordingState.Starting &&
                            helper?.isRecording != true
                        ) {
                            finishRecording(session, publish = false, message = "Video recorder did not start")
                        }
                    },
                    RECORDING_START_TIMEOUT_MS,
                )
            } catch (error: Exception) {
                runCatching { descriptor?.close() }
                pending?.let(mediaStore::discard)
                synchronized(mediaSessionLock) { activeRecording = null }
                mutableControls.value = mutableControls.value.copy(recording = RecordingState.Idle)
                mutableEvents.tryEmit(CameraUiEvent.Message(error.message ?: "Video recording failed"))
            }
        }
    }

    private fun stopRecordingSafely(reason: String) {
        val recordingState = mutableControls.value.recording
        if (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopping) return
        val session = synchronized(mediaSessionLock) { activeRecording } ?: run {
            mutableControls.value = mutableControls.value.copy(recording = RecordingState.Idle)
            return
        }
        mutableControls.value = mutableControls.value.copy(recording = RecordingState.Stopping)
        debug("[UVC] Stopping recording: $reason")
        runCatching { helper?.stopRecording() }
            .onFailure {
                finishRecording(session, publish = false, message = "Video recording could not stop safely")
                return
            }

        mainHandler.postDelayed(
            {
                if (!isActiveRecording(session)) return@postDelayed
                val hasData = runCatching { session.descriptor.statSize > 0L }.getOrDefault(false)
                finishRecording(
                    session,
                    publish = hasData && session.started.get(),
                    message = if (hasData && session.started.get()) {
                        "Recording interrupted; partial video saved"
                    } else {
                        "Recording interrupted"
                    },
                )
            },
            RECORDING_FINALIZE_TIMEOUT_MS,
        )
    }

    private fun finishRecording(
        session: ActiveRecording,
        publish: Boolean,
        message: String,
    ) {
        mediaExecutor.execute {
            val ownsSession = synchronized(mediaSessionLock) {
                if (activeRecording !== session) false else {
                    activeRecording = null
                    true
                }
            }
            if (!ownsSession) return@execute
            runCatching { session.descriptor.close() }
            if (publish) {
                runCatching { mediaStore.publish(session.media) }
                    .onFailure { mediaStore.discard(session.media) }
            } else {
                mediaStore.discard(session.media)
            }
            mutableControls.value = mutableControls.value.copy(recording = RecordingState.Idle)
            mutableEvents.tryEmit(
                CameraUiEvent.Message(
                    if (publish) message else message.ifBlank { "Video recording failed" },
                ),
            )
        }
    }

    private fun isActiveRecording(session: ActiveRecording): Boolean =
        synchronized(mediaSessionLock) { activeRecording === session }

    private fun configureMediaCapture(size: Size?) {
        helper?.imageCaptureConfig?.apply {
            setJpegCompressionQuality(JPEG_QUALITY)
            helper?.imageCaptureConfig = this
        }
        val fps = size?.fps?.takeIf { it > 0 } ?: DEFAULT_RECORDING_FPS
        helper?.videoCaptureConfig?.apply {
            setAudioCaptureEnable(false)
            setVideoFrameRate(fps)
            setIFrameInterval(RECORDING_I_FRAME_INTERVAL_SECONDS)
            helper?.videoCaptureConfig = this
        }
    }

    private fun configureVideoCapture(stream: ActiveStream) {
        helper?.videoCaptureConfig?.apply {
            setAudioCaptureEnable(false)
            setVideoFrameRate(stream.fps.takeIf { it > 0 } ?: DEFAULT_RECORDING_FPS)
            setBitRate(recordingBitRate(stream.width, stream.height))
            setIFrameInterval(RECORDING_I_FRAME_INTERVAL_SECONDS)
            helper?.videoCaptureConfig = this
        }
    }

    private fun recordingBitRate(width: Int, height: Int): Int = when {
        width * height >= 1920 * 1080 -> 8_000_000
        width * height >= 1280 * 720 -> 5_000_000
        else -> 2_500_000
    }

    private fun applyRendererTransform() {
        val transform = mutableControls.value.transform
        val mirrorMode = when {
            transform.mirrorHorizontal && transform.mirrorVertical -> MirrorMode.MIRROR_BOTH
            transform.mirrorHorizontal -> MirrorMode.MIRROR_HORIZONTAL
            transform.mirrorVertical -> MirrorMode.MIRROR_VERTICAL
            else -> MirrorMode.MIRROR_NORMAL
        }
        helper?.previewConfig?.apply {
            setRotation(transform.rotationDegrees)
            setMirror(mirrorMode)
            helper?.previewConfig = this
        }
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
        stopRecordingSafely("Camera disconnected")
        cancelActivePhoto()
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
        stopArtifactDiagnostics()
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
        if (BuildConfig.DEBUG) {
            artifactLog("[DEVICE] ${Build.MANUFACTURER} ${Build.MODEL}")
            artifactLog("[ANDROID] ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
            artifactLog("[DEVICE] hardware=${Build.HARDWARE} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        }
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
            repeat(usbInterface.endpointCount) { endpointIndex ->
                debug("[USB] ${endpointDescription(index, endpointIndex, device)}")
            }
        }
    }

    private fun startArtifactDiagnostics(device: UsbDevice, size: Size?) {
        if (!artifactMonitor.enabled || size == null) return
        val modes = helper?.supportedSizeList.orEmpty().map(::modeDescription)
        val endpoints = endpointDescriptions(device)
        val selectedEndpoint = inferSelectedEndpoint(device)
        val mode = modeDescription(size)
        val isMjpeg = formatLabel(size) == "MJPEG"
        artifactMonitor.configure(
            mode = mode,
            width = size.width,
            height = size.height,
            fps = size.fps,
            isMjpeg = isMjpeg,
            availableModes = modes,
            endpoints = endpoints,
            selectedEndpoint = selectedEndpoint,
        )
        artifactLog("[UVC] Selected format=${formatLabel(size)} type=${size.type}")
        artifactLog("[UVC] Resolution=${size.width}x${size.height}")
        artifactLog("[UVC] FPS=${size.fps}")
        artifactLog("[UVC] FrameInterval100ns=${size.fps.takeIf { it > 0 }?.let { 10_000_000L / it } ?: "unknown"}")
        artifactLog(
            "[UVC] ExpectedRawFrameBytes=" +
                if (isMjpeg) "variable (MJPEG)" else (size.width.toLong() * size.height * 2L),
        )
        artifactLog("[UVC] ExpectedDecodedRgbxBytes=${size.width.toLong() * size.height * 4L}")
        artifactLog("[UVC] SelectedEndpoint=$selectedEndpoint")
        modes.forEach { artifactLog("[UVC] AvailableMode=$it") }
        endpoints.forEach { artifactLog("[USB] EndpointCandidate=$it") }
        artifactLog(
            "[UVC_DIAG] Native UVC payload/FID/EOF/ERR integrity counters enabled",
        )
        runCatching {
            helper?.setFrameCallback(
                IFrameCallback { buffer -> artifactMonitor.onDecodedRgbx(buffer, size.width, size.height) },
                UVCCamera.PIXEL_FORMAT_RGBX,
            )
        }.onFailure { artifactLog("[UVC_DIAG] Unable to attach post-decode frame callback: ${it.message}") }
        mainHandler.removeCallbacks(artifactPublishRunnable)
        publishArtifactDiagnostics()
        mainHandler.postDelayed(artifactPublishRunnable, ARTIFACT_SNAPSHOT_INTERVAL_MS)
    }

    private fun stopArtifactDiagnostics() {
        if (!artifactMonitor.enabled) return
        mainHandler.removeCallbacks(artifactPublishRunnable)
        runCatching { helper?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGBX) }
        publishArtifactDiagnostics()
    }

    private fun publishArtifactDiagnostics() {
        if (!artifactMonitor.enabled) return
        val nativeIntegrity = runCatching {
            NativeUvcIntegrityStats.from(UVCCamera.getIntegrityStats())
        }.onFailure {
            artifactLog("[UVC_DIAG] Unable to read native integrity counters: ${it.message}")
        }.getOrDefault(NativeUvcIntegrityStats())
        artifactLog(
            "[UVC_NATIVE] packets=${nativeIntegrity.uvcPacketsTotal} " +
                "packetErr=${nativeIntegrity.uvcPacketsErr} eof=${nativeIntegrity.framesCompletedWithEof} " +
                "dropErr=${nativeIntegrity.framesDroppedErr} " +
                "dropMissingEof=${nativeIntegrity.framesDroppedMissingEof} " +
                "dropFid=${nativeIntegrity.framesDroppedFidTransition} " +
                "dropSize=${nativeIntegrity.framesDroppedSizeMismatch} " +
                "yuy2Accepted=${nativeIntegrity.rawYuy2FramesAccepted} " +
                "yuy2Dropped=${nativeIntegrity.rawYuy2FramesDropped}",
        )
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            artifact = artifactMonitor.snapshot(
                transform = mutableControls.value.transform,
                recordingState = mutableControls.value.recording,
                nativeIntegrity = nativeIntegrity,
            ),
        )
    }

    private fun modeDescription(size: Size): String {
        val rawBandwidth = if (formatLabel(size) == "MJPEG") {
            "compressed-bandwidth=variable"
        } else {
            val bytesPerSecond = size.width.toLong() * size.height * 2L * size.fps.coerceAtLeast(0)
            "raw-bandwidth=${bytesPerSecond}B/s"
        }
        return "${size.width}x${size.height} ${formatLabel(size)} ${size.fps}fps " +
            "type=${size.type} fpsList=${size.fpsList.orEmpty()} $rawBandwidth"
    }

    private fun endpointDescriptions(device: UsbDevice): List<String> = buildList {
        repeat(device.interfaceCount) { interfaceIndex ->
            val usbInterface = device.getInterface(interfaceIndex)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                usbInterface.interfaceSubclass == UVC_VIDEO_STREAMING_SUBCLASS
            ) {
                repeat(usbInterface.endpointCount) { endpointIndex ->
                    add(endpointDescription(interfaceIndex, endpointIndex, device))
                }
            }
        }
    }

    private fun endpointDescription(interfaceIndex: Int, endpointIndex: Int, device: UsbDevice): String {
        val usbInterface = device.getInterface(interfaceIndex)
        val endpoint = usbInterface.getEndpoint(endpointIndex)
        val type = when (endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            else -> "UNKNOWN(${endpoint.type})"
        }
        val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        return "interfaceIndex=$interfaceIndex interfaceId=${usbInterface.id} " +
            "alternate=${usbInterface.alternateSetting} endpointIndex=$endpointIndex " +
            "address=0x${endpoint.address.toString(16)} direction=$direction type=$type " +
            "maxPacketSize=${endpoint.maxPacketSize} interval=${endpoint.interval}"
    }

    private fun inferSelectedEndpoint(device: UsbDevice): String {
        val candidates = endpointDescriptions(device)
        if (candidates.isEmpty()) return "No VideoStreaming endpoint exposed by Android descriptors"
        val endpointTypes = candidates.mapNotNull { description ->
            when {
                "type=ISOCHRONOUS" in description -> "ISOCHRONOUS"
                "type=BULK" in description -> "BULK"
                else -> null
            }
        }.distinct()
        return if (candidates.size == 1) {
            "Single candidate (inferred, native confirmation unavailable): ${candidates.single()}"
        } else if (endpointTypes.size == 1) {
            "${endpointTypes.single()} inferred from ${candidates.size} candidates; native alt setting unavailable"
        } else {
            "Not exposed; ${candidates.size} endpoint candidates have types ${endpointTypes.joinToString()}"
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

    private data class ActivePhoto(
        val media: MediaStorePublisher.PendingMedia,
        val output: OutputStream,
    )

    private data class ActiveRecording(
        val media: MediaStorePublisher.PendingMedia,
        val descriptor: ParcelFileDescriptor,
        val started: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        const val ACTION_USB_PERMISSION = "com.rammy.aigun.USB_PERMISSION"
        const val USB_PERMISSION_REQUEST_CODE = 4107
        const val FALLBACK_RETRY_DELAY_MS = 120L
        const val USB_TAG = "RammyUsb"
        const val METRICS_TAG = "RammyCameraMetrics"
        const val JPEG_QUALITY = 95
        const val DEFAULT_RECORDING_FPS = 30
        const val RECORDING_I_FRAME_INTERVAL_SECONDS = 1
        const val RECORDING_START_TIMEOUT_MS = 8_000L
        const val RECORDING_FINALIZE_TIMEOUT_MS = 5_000L
        const val ARTIFACT_SNAPSHOT_INTERVAL_MS = 1_000L
        const val UVC_VIDEO_STREAMING_SUBCLASS = 2

        const val UVC_FORMAT_UNCOMPRESSED = 4
        const val UVC_FRAME_UNCOMPRESSED = 5
        const val UVC_FORMAT_MJPEG = 6
        const val UVC_FRAME_MJPEG = 7
    }
}
