package com.rammy.aigun.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rammy.aigun.camera.CameraConnectionState
import com.rammy.aigun.camera.CameraViewModel
import com.rammy.aigun.camera.FirstFrameTextureView

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var previewView by remember { mutableStateOf<FirstFrameTextureView?>(null) }
    val isStreaming = state is CameraConnectionState.Streaming

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

        if (!isStreaming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6050708)),
            )
            ConnectionOverlay(
                state = state,
                onAllow = viewModel::allowAndConnect,
                onRetry = viewModel::retry,
            )
        }

        MonitorChrome(state = state)
    }
}

@Composable
private fun MonitorChrome(state: CameraConnectionState) {
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
                    Text("RAMMY AI GUN", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                }
            }

            if (isStreaming) {
                GlassPanel {
                    Row {
                        MonitorTool(Icons.Rounded.Info, "Info")
                        MonitorTool(Icons.Rounded.ScreenRotation, "Rotate")
                        MonitorTool(Icons.Rounded.AspectRatio, "Fit")
                        MonitorTool(Icons.Rounded.Fullscreen, "Full screen")
                        MonitorTool(Icons.Rounded.VisibilityOff, "Hide controls")
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
                Text("Rotate your phone for full view", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
        }

        if (isStreaming) {
            BottomControls()
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun BottomControls() {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DisabledAction(Icons.Rounded.Collections, "Gallery")
            DisabledAction(Icons.Rounded.PhotoCamera, "Photo", prominent = true)
            DisabledAction(Icons.Rounded.FiberManualRecord, "Record", record = true)
            DisabledAction(Icons.Rounded.Settings, "Settings")
        }
    }
}

@Composable
private fun ConnectionOverlay(
    state: CameraConnectionState,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
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
            detail = "Checking USB host support…"
            icon = Icons.Rounded.Usb
            spinning = true
        }
        CameraConnectionState.UsbHostUnavailable -> {
            title = "USB camera is not supported"
            detail = "This device does not expose Android USB host mode."
            icon = Icons.Rounded.Usb
            spinning = false
        }
        CameraConnectionState.Waiting -> {
            title = "Connect your USB camera"
            detail = "Rammy AI Gun will connect automatically."
            icon = Icons.Rounded.Usb
            spinning = false
        }
        is CameraConnectionState.Detected -> {
            title = "USB Camera detected"
            detail = "Rammy AI Gun needs USB access to display the external camera connected to your phone."
            icon = Icons.Rounded.Usb
            spinning = false
            button = "Allow & Connect"
            action = onAllow
        }
        is CameraConnectionState.Connecting -> {
            title = "Connecting camera…"
            detail = state.device.name
            icon = Icons.Rounded.Usb
            spinning = true
        }
        CameraConnectionState.Disconnected -> {
            title = "Camera disconnected"
            detail = "Waiting for camera…"
            icon = Icons.Rounded.Usb
            spinning = true
        }
        is CameraConnectionState.PermissionDenied -> {
            title = "Camera access is required"
            detail = "USB access is required to display your camera."
            icon = Icons.Rounded.Usb
            spinning = false
            button = "Try again"
            action = onAllow
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
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = ElectricCyan)
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
private fun MonitorTool(icon: ImageVector, description: String) {
    IconButton(onClick = {}, enabled = false, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DisabledAction(icon: ImageVector, label: String, prominent: Boolean = false, record: Boolean = false) {
    Column(
        modifier = Modifier.alpha(0.48f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(if (prominent) 52.dp else 44.dp),
            shape = CircleShape,
            color = when {
                prominent -> Color.White
                record -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                else -> Color.White.copy(alpha = 0.08f)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    label,
                    tint = when {
                        prominent -> MonitorBlack
                        record -> MaterialTheme.colorScheme.error
                        else -> Color.White
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.72f))
    }
}

