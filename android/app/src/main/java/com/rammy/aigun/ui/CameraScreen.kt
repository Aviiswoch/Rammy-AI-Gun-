package com.rammy.aigun.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.rammy.aigun.BuildConfig
import com.rammy.aigun.camera.CameraControlsState
import com.rammy.aigun.camera.CameraConnectionState
import com.rammy.aigun.camera.CameraUiEvent
import com.rammy.aigun.camera.CameraViewModel
import com.rammy.aigun.camera.DiagnosticTextExporter
import com.rammy.aigun.camera.FirstFrameTextureView
import com.rammy.aigun.camera.RecordingState
import com.rammy.aigun.camera.ActiveStream
import com.rammy.aigun.camera.PreviewDisplayMode
import com.rammy.aigun.camera.UsbDiagnostics
import com.herohan.uvcapp.CameraWatermark
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var previewView by remember { mutableStateOf<FirstFrameTextureView?>(null) }
    var showUsbDiagnostics by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    var messageGeneration by remember { mutableStateOf(0) }
    var shutterVisible by remember { mutableStateOf(false) }
    var cameraPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var pendingLegacyMediaAction by remember { mutableStateOf<PendingMediaAction?>(null) }
    val isStreaming = state is CameraConnectionState.Streaming
    val activeStream = (state as? CameraConnectionState.Streaming)?.stream
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permanentlyDenied = !granted && cameraPermissionRequested &&
            (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        viewModel.onCameraPermissionResult(granted, permanentlyDenied)
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingLegacyMediaAction
        pendingLegacyMediaAction = null
        if (granted) {
            when (action) {
                PendingMediaAction.Photo -> viewModel.takePhoto()
                PendingMediaAction.Record -> viewModel.toggleRecording()
                null -> Unit
            }
        } else {
            val generation = ++messageGeneration
            transientMessage = "Storage access is required on this Android version"
            scope.launch {
                delay(1_600)
                if (messageGeneration == generation) transientMessage = null
            }
        }
    }

    fun runMediaAction(action: PendingMediaAction) {
        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingLegacyMediaAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            when (action) {
                PendingMediaAction.Photo -> viewModel.takePhoto()
                PendingMediaAction.Record -> viewModel.toggleRecording()
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CameraUiEvent.Message -> {
                    val generation = ++messageGeneration
                    transientMessage = event.text
                    launch {
                        delay(1_600)
                        if (messageGeneration == generation) transientMessage = null
                    }
                }
                CameraUiEvent.Shutter -> {
                    shutterVisible = true
                    launch {
                        delay(110)
                        shutterVisible = false
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewView?.let(viewModel::detachPreviewView) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonitorBlack),
    ) {
        AndroidView(
            factory = { context ->
                FirstFrameTextureView(context).also { view ->
                    previewView = view
                    viewModel.attachPreviewView(view)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        activeStream?.let { stream ->
            LiveCameraWatermark(
                stream = stream,
                rotationDegrees = controls.transform.rotationDegrees,
                displayMode = controls.transform.displayMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isStreaming && !controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        controlsVisible = true
                    },
            )
        }

        if (!isStreaming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6050708)),
            )
            ConnectionOverlay(
                state = state,
                onAllow = {
                    when (state) {
                        is CameraConnectionState.CameraPermissionRequired,
                        is CameraConnectionState.CameraPermissionDenied,
                        -> {
                            cameraPermissionRequested = true
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        else -> viewModel.allowAndConnect()
                    }
                },
                onRetry = viewModel::retry,
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )
        }

        if (controlsVisible || !isStreaming) {
            MonitorChrome(
                state = state,
                controls = controls,
                onInfo = { showInfo = true },
                onRotate = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.rotatePreviewClockwise()
                },
                onDisplayMode = viewModel::toggleDisplayMode,
                onHideControls = { controlsVisible = false },
                onPhoto = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    runMediaAction(PendingMediaAction.Photo)
                },
                onRecord = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    runMediaAction(PendingMediaAction.Record)
                },
                onSettings = { showUsbDiagnostics = true },
            )
        }

        AnimatedVisibility(
            visible = transientMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            GlassPanel {
                Text(transientMessage.orEmpty(), fontSize = 13.sp)
            }
        }

        AnimatedVisibility(
            visible = shutterVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.16f)),
            )
        }
    }

    if (showInfo) {
        CameraInfoDialog(
            state = state,
            diagnostics = diagnostics,
            controls = controls,
            onDismiss = { showInfo = false },
        )
    }

    if (showUsbDiagnostics) {
        UsbDiagnosticsDialog(
            diagnostics = diagnostics,
            onDismiss = { showUsbDiagnostics = false },
        )
    }
}

@Composable
private fun LiveCameraWatermark(
    stream: ActiveStream,
    rotationDegrees: Int,
    displayMode: PreviewDisplayMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var dateText by remember { mutableStateOf(CameraWatermark.currentDateText()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val current = CameraWatermark.currentDateText()
            if (current != dateText) dateText = current
        }
    }

    val artwork = remember(context, dateText) {
        CameraWatermark.createArtwork(context, dateText)
    }
    val image = remember(artwork) { artwork.asImageBitmap() }
    DisposableEffect(artwork) {
        onDispose { artwork.recycle() }
    }

    BoxWithConstraints(modifier) {
        val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val sourceWidth = if (quarterTurn) stream.height else stream.width
        val sourceHeight = if (quarterTurn) stream.width else stream.height
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1).toFloat()
        val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val contentWidth = when {
            displayMode == PreviewDisplayMode.Fill -> maxWidth
            sourceAspect > containerAspect -> maxWidth
            else -> maxHeight * sourceAspect
        }
        val contentHeight = when {
            displayMode == PreviewDisplayMode.Fill -> maxHeight
            sourceAspect > containerAspect -> maxWidth / sourceAspect
            else -> maxHeight
        }
        val navigationInset = with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val contentBottomGap = (maxHeight - contentHeight) / 2
        val navigationClearance = (navigationInset - contentBottomGap).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(contentWidth, contentHeight),
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = contentWidth * CameraWatermark.FRAME_MARGIN_FRACTION,
                        bottom = contentWidth * CameraWatermark.FRAME_MARGIN_FRACTION +
                            navigationClearance,
                    )
                    .width(contentWidth * CameraWatermark.FRAME_WIDTH_FRACTION)
                    .aspectRatio(
                        CameraWatermark.TEXTURE_WIDTH.toFloat() /
                            CameraWatermark.TEXTURE_HEIGHT.toFloat(),
                    ),
            )
        }
    }
}

private enum class PendingMediaAction { Photo, Record }

@Composable
private fun MonitorChrome(
    state: CameraConnectionState,
    controls: CameraControlsState,
    onInfo: () -> Unit,
    onRotate: () -> Unit,
    onDisplayMode: () -> Unit,
    onHideControls: () -> Unit,
    onPhoto: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val isStreaming = state is CameraConnectionState.Streaming
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (isStreaming) ElectricCyan else Color(0xFF586064), CircleShape),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "RAMMY AI GUN",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                }
            }

            GlassPanel {
                Row {
                    if (isStreaming) {
                        MonitorTool(Icons.Rounded.Info, "Info", onInfo)
                        MonitorTool(
                            Icons.Rounded.ScreenRotation,
                            "Rotate ${controls.transform.rotationDegrees} degrees",
                            onRotate,
                        )
                        MonitorTool(
                            Icons.Rounded.AspectRatio,
                            controls.transform.displayMode.name,
                            onDisplayMode,
                        )
                        MonitorTool(Icons.Rounded.VisibilityOff, "Hide controls", onHideControls)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Settings,
                            "Settings and USB diagnostics",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isStreaming && isPortrait,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            GlassPanel {
                Text(
                    "Rotate your phone for full view",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
        }

        if (isStreaming) {
            BottomControls(
                controls = controls,
                onPhoto = onPhoto,
                onRecord = onRecord,
            )
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun BottomControls(
    controls: CameraControlsState,
    onPhoto: () -> Unit,
    onRecord: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val recording = controls.recording
    LaunchedEffect(recording) {
        val active = recording as? RecordingState.Recording ?: run {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - active.startedAtElapsedMs) / 1_000L
            delay(250)
        }
    }
    val recordingLabel = when (recording) {
        RecordingState.Idle -> "Record"
        RecordingState.Starting -> "Starting"
        is RecordingState.Recording -> formatElapsed(elapsedSeconds)
        RecordingState.Stopping -> "Saving"
    }
    val isRecordingActive = recording is RecordingState.Recording || recording == RecordingState.Stopping

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraAction(
                icon = Icons.Rounded.PhotoCamera,
                label = if (controls.photoCaptureInProgress) "Saving" else "Photo",
                onClick = onPhoto,
                enabled = !controls.photoCaptureInProgress,
                prominent = true,
            )
            CameraAction(
                icon = if (isRecordingActive) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                label = recordingLabel,
                onClick = onRecord,
                enabled = recording == RecordingState.Idle || recording is RecordingState.Recording,
                record = true,
                active = isRecordingActive,
            )
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String =
    "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)

@Composable
private fun ConnectionOverlay(
    state: CameraConnectionState,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val title: String
    val detail: String
    val icon: ImageVector
    val spinning: Boolean
    var button: String? = null
    var action: (() -> Unit)? = null

    when (state) {
        CameraConnectionState.Starting -> {
            title = "Preparing camera"
            detail = "Checking USB host support..."
            icon = Icons.Rounded.Usb
            spinning = true
        }
        CameraConnectionState.UsbHostUnavailable -> {
            title = "USB host mode is not supported by this device."
            detail = "A compatible Android USB Host device is required."
            icon = Icons.Rounded.Usb
            spinning = false
        }
        CameraConnectionState.WaitingForDevice -> {
            title = "Connect your USB camera"
            detail = "Rammy AI Gun will connect automatically."
            icon = Icons.Rounded.Usb
            spinning = false
        }
        is CameraConnectionState.DeviceDetected -> {
            title = "USB camera detected"
            detail = "Preparing the permission request..."
            icon = Icons.Rounded.Usb
            spinning = true
        }
        is CameraConnectionState.CameraPermissionRequired -> {
            title = "USB camera detected"
            detail = "Rammy AI Gun needs camera/USB access to display the external camera connected to your phone."
            icon = Icons.Rounded.Usb
            spinning = false
            button = "Allow & Connect"
            action = onAllow
        }
        is CameraConnectionState.CameraPermissionDenied -> {
            title = "Camera access is required"
            detail = "Camera access is required to display your USB camera."
            icon = Icons.Rounded.Usb
            spinning = false
            button = if (state.permanentlyDenied) "Open Settings" else "Allow & Connect"
            action = if (state.permanentlyDenied) onOpenSettings else onAllow
        }
        is CameraConnectionState.UsbPermissionRequired -> {
            title = "Allow USB camera access"
            detail = "Waiting for the Android USB permission response."
            icon = Icons.Rounded.Usb
            spinning = true
        }
        is CameraConnectionState.OpeningDevice -> {
            title = "Connecting camera..."
            detail = state.device.name
            icon = Icons.Rounded.Usb
            spinning = true
        }
        is CameraConnectionState.NegotiatingStream -> {
            title = "Starting live view..."
            detail = "Negotiating a supported low-latency UVC stream."
            icon = Icons.Rounded.Usb
            spinning = true
        }
        CameraConnectionState.Disconnected -> {
            title = "Camera disconnected"
            detail = "Waiting for camera..."
            icon = Icons.Rounded.Usb
            spinning = true
        }
        is CameraConnectionState.UsbPermissionDenied -> {
            title = "USB camera permission is required"
            detail = "USB camera permission is required to display the external camera."
            icon = Icons.Rounded.Usb
            spinning = false
            button = "Allow & Connect"
            action = onAllow
        }
        is CameraConnectionState.NoDeviceEnumerated -> {
            title = "USB camera could not be detected"
            detail = if (state.connectedDeviceCount == 0) {
                "No USB camera was enumerated. Check that your adapter supports OTG/USB Host mode and that the camera has enough power."
            } else {
                "A USB device was found, but it does not expose a standard UVC video interface."
            }
            icon = Icons.Rounded.Usb
            spinning = false
            button = "Check again"
            action = onRetry
        }
        is CameraConnectionState.Error -> {
            title = "Camera could not start"
            detail = state.message
            icon = Icons.Rounded.Refresh
            spinning = false
            if (state.retryable) {
                button = "Reconnect"
                action = onRetry
            }
        }
        is CameraConnectionState.Streaming -> return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = ElectricCyan.copy(alpha = 0.10f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(92.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ElectricCyan, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        if (spinning) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = ElectricCyan,
            )
        }
        if (button != null && action != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = action,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(button, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun UsbDiagnosticsDialog(diagnostics: UsbDiagnostics, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF111719),
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Settings / Diagnostics / USB", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text(
                    diagnostics.asText(),
                    color = Color.White.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Rammy USB diagnostics", diagnostics.asText()),
                            )
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    ) {
                        Text("Copy diagnostics")
                    }
                    if (BuildConfig.DEBUG) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            DiagnosticTextExporter.export(context, diagnostics.asText())
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        result.fold(
                                            onSuccess = { "Diagnostics exported: $it" },
                                            onFailure = { it.message ?: "Diagnostics export failed" },
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.10f),
                            ),
                        ) {
                            Text("Export", color = Color.White)
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.10f),
                        ),
                    ) {
                        Text("Close", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraInfoDialog(
    state: CameraConnectionState,
    diagnostics: UsbDiagnostics,
    controls: CameraControlsState,
    onDismiss: () -> Unit,
) {
    val stream = (state as? CameraConnectionState.Streaming)?.stream
    val recordingLabel = when (controls.recording) {
        RecordingState.Idle -> "Idle"
        RecordingState.Starting -> "Starting"
        is RecordingState.Recording -> "Recording"
        RecordingState.Stopping -> "Saving"
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xEE111719),
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Camera information", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                InfoLine("Camera connected", if (stream != null) "Yes" else "No")
                InfoLine("Camera", stream?.device?.name ?: "Not connected")
                InfoLine("Vendor ID", stream?.device?.vendorId?.toString() ?: "N/A")
                InfoLine("Product ID", stream?.device?.productId?.toString() ?: "N/A")
                InfoLine(
                    "Resolution",
                    stream?.let { "${it.width} × ${it.height}" } ?: diagnostics.resolution,
                )
                InfoLine("FPS", stream?.fps?.toString() ?: diagnostics.fps)
                InfoLine("Stream", stream?.format ?: diagnostics.format)
                InfoLine("View rotation", "${controls.transform.rotationDegrees}°")
                InfoLine("Display", controls.transform.displayMode.name)
                InfoLine("Recording", recordingLabel)
                InfoLine("App", "Rammy AI Gun")
                InfoLine("Version", BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
        Spacer(Modifier.width(20.dp))
        Text(value, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = GlassBlack,
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        content = {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) { content() }
        },
    )
}

@Composable
private fun MonitorTool(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            description,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CameraAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    prominent: Boolean = false,
    record: Boolean = false,
    active: Boolean = false,
) {
    Column(
        modifier = Modifier.alpha(if (enabled) 1f else 0.48f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(if (prominent) 52.dp else 44.dp),
            shape = CircleShape,
            color = when {
                prominent -> Color.White
                record && active -> MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
                record -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                else -> Color.White.copy(alpha = 0.08f)
            },
        ) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(
                    icon,
                    label,
                    tint = when {
                        prominent -> MonitorBlack
                        record && active -> Color.White
                        record -> MaterialTheme.colorScheme.error
                        else -> Color.White
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.82f))
    }
}
