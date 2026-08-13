package com.rammy.aigun.camera

import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class NativeUvcIntegrityStats(
    val uvcPacketsTotal: Long = 0,
    val uvcPacketsErr: Long = 0,
    val framesCompletedWithEof: Long = 0,
    val framesDroppedErr: Long = 0,
    val framesDroppedMissingEof: Long = 0,
    val framesDroppedFidTransition: Long = 0,
    val framesDroppedSizeMismatch: Long = 0,
    val rawYuy2FramesAccepted: Long = 0,
    val rawYuy2FramesDropped: Long = 0,
) {
    companion object {
        fun from(values: LongArray): NativeUvcIntegrityStats = NativeUvcIntegrityStats(
            uvcPacketsTotal = values.getOrElse(0) { 0 },
            uvcPacketsErr = values.getOrElse(1) { 0 },
            framesCompletedWithEof = values.getOrElse(2) { 0 },
            framesDroppedErr = values.getOrElse(3) { 0 },
            framesDroppedMissingEof = values.getOrElse(4) { 0 },
            framesDroppedFidTransition = values.getOrElse(5) { 0 },
            framesDroppedSizeMismatch = values.getOrElse(6) { 0 },
            rawYuy2FramesAccepted = values.getOrElse(7) { 0 },
            rawYuy2FramesDropped = values.getOrElse(8) { 0 },
        )
    }
}

data class UvcArtifactDiagnostics(
    val enabled: Boolean = false,
    val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    val model: String = Build.MODEL.orEmpty(),
    val androidRelease: String = Build.VERSION.RELEASE.orEmpty(),
    val sdkLevel: Int = Build.VERSION.SDK_INT,
    val hardware: String = Build.HARDWARE.orEmpty(),
    val supportedAbis: String = Build.SUPPORTED_ABIS.joinToString(),
    val library: String = "UVCAndroid 1.0.13 (vendored native integrity build)",
    val currentMode: String = "Not negotiated",
    val availableModes: List<String> = emptyList(),
    val endpointCandidates: List<String> = emptyList(),
    val selectedEndpoint: String = "Not exposed by UVCAndroid",
    val frameInterval100ns: Long? = null,
    val expectedRawFrameBytes: Long? = null,
    val expectedDecodedRgbxBytes: Long? = null,
    val lastDecodedRgbxBytes: Long = 0,
    val decodedFramesObserved: Long = 0,
    val renderedFramesObserved: Long = 0,
    val renderBacklogEstimate: Long = 0,
    val decodedSizeMismatches: Long = 0,
    val suspiciousDecodedColorFrames: Long = 0,
    val suspiciousGreenFrames: Long = 0,
    val suspiciousMagentaFrames: Long = 0,
    val callbackBufferObjectsObserved: Long = 0,
    val callbackBufferObjectReuseObserved: Long = 0,
    val lastDecodedFrameId: Long = 0,
    val lastRenderedFrameId: Long = 0,
    val lastDecodeCallbackElapsedNs: Long = 0,
    val lastRenderCallbackElapsedNs: Long = 0,
    val transform: String = "rotation=0, mirrorH=false, mirrorV=false, display=Fit",
    val recording: String = "Idle",
    val nativeIntegrity: NativeUvcIntegrityStats = NativeUvcIntegrityStats(),
) {
    fun asText(): String = buildString {
        appendLine("UVC artifact diagnostics (debug-only): ${if (enabled) "ENABLED" else "DISABLED"}")
        appendLine("Device: $manufacturer $model")
        appendLine("Android: $androidRelease (SDK $sdkLevel)")
        appendLine("Hardware: $hardware")
        appendLine("CPU ABIs: $supportedAbis")
        appendLine("Pipeline library: $library")
        appendLine("Current mode: $currentMode")
        appendLine("Frame interval (100 ns): ${frameInterval100ns ?: "Unavailable"}")
        appendLine("Expected raw frame bytes: ${expectedRawFrameBytes ?: "Variable/unavailable"}")
        appendLine("Expected decoded RGBX bytes: ${expectedDecodedRgbxBytes ?: "Unavailable"}")
        appendLine("Last decoded RGBX bytes: $lastDecodedRgbxBytes")
        appendLine("Decoded frames observed: $decodedFramesObserved")
        appendLine("Rendered frames observed: $renderedFramesObserved")
        appendLine("Decode/render backlog estimate: $renderBacklogEstimate (not a definitive drop count)")
        appendLine("Decoded-size mismatches: $decodedSizeMismatches")
        appendLine("Suspicious decoded color frames: $suspiciousDecodedColorFrames (heuristic only)")
        appendLine("Suspicious green frames: $suspiciousGreenFrames")
        appendLine("Suspicious magenta frames: $suspiciousMagentaFrames")
        appendLine("Callback buffer objects observed: $callbackBufferObjectsObserved")
        appendLine("Callback buffer object reuse observed: $callbackBufferObjectReuseObserved")
        appendLine("Last decoded frame ID: $lastDecodedFrameId")
        appendLine("Last rendered frame ID: $lastRenderedFrameId")
        appendLine("Last decode callback elapsed ns: $lastDecodeCallbackElapsedNs")
        appendLine("Last render callback elapsed ns: $lastRenderCallbackElapsedNs")
        appendLine("Transform: $transform")
        appendLine("Recording: $recording")
        appendLine("Selected native endpoint: $selectedEndpoint")
        appendLine("Endpoint candidates:")
        if (endpointCandidates.isEmpty()) appendLine("  Unavailable")
        else endpointCandidates.forEach { appendLine("  $it") }
        appendLine("Available modes:")
        if (availableModes.isEmpty()) appendLine("  Unavailable")
        else availableModes.forEach { appendLine("  $it") }
        appendLine("Native frame-integrity counters:")
        appendLine("  uvcPacketsTotal: ${nativeIntegrity.uvcPacketsTotal}")
        appendLine("  uvcPacketsErr: ${nativeIntegrity.uvcPacketsErr}")
        appendLine("  framesCompletedWithEOF: ${nativeIntegrity.framesCompletedWithEof}")
        appendLine("  framesDroppedErr: ${nativeIntegrity.framesDroppedErr}")
        appendLine("  framesDroppedMissingEOF: ${nativeIntegrity.framesDroppedMissingEof}")
        appendLine("  framesDroppedFIDTransition: ${nativeIntegrity.framesDroppedFidTransition}")
        appendLine("  framesDroppedSizeMismatch: ${nativeIntegrity.framesDroppedSizeMismatch}")
        appendLine("  rawYuy2FramesAccepted: ${nativeIntegrity.rawYuy2FramesAccepted}")
        appendLine("  rawYuy2FramesDropped: ${nativeIntegrity.rawYuy2FramesDropped}")
        appendLine("Raw MJPEG validation: UNAVAILABLE; callback contains post-decode RGBX")
        append("Native integrity guard discards invalid raw frames before conversion/rendering.")
    }
}

internal class UvcArtifactMonitor(
    val enabled: Boolean,
    private val logger: (String) -> Unit,
) {
    private val decodedFrames = AtomicLong()
    private val renderedFrames = AtomicLong()
    private val decodedSizeMismatches = AtomicLong()
    private val suspiciousFrames = AtomicLong()
    private val suspiciousGreenFrames = AtomicLong()
    private val suspiciousMagentaFrames = AtomicLong()
    private val lastDecodedBytes = AtomicLong()
    private val lastDecodeNs = AtomicLong()
    private val lastRenderNs = AtomicLong()
    private val observedBufferIds = linkedSetOf<Int>()
    private var reusedBufferObjects = 0L
    private var previousTileColors: IntArray? = null

    @Volatile private var mode = "Not negotiated"
    @Volatile private var availableModes: List<String> = emptyList()
    @Volatile private var endpoints: List<String> = emptyList()
    @Volatile private var selectedEndpoint = "Not exposed by UVCAndroid"
    @Volatile private var frameInterval100ns: Long? = null
    @Volatile private var expectedRawBytes: Long? = null
    @Volatile private var expectedRgbxBytes: Long? = null

    @Synchronized
    fun configure(
        mode: String,
        width: Int,
        height: Int,
        fps: Int,
        isMjpeg: Boolean,
        availableModes: List<String>,
        endpoints: List<String>,
        selectedEndpoint: String,
    ) {
        decodedFrames.set(0)
        renderedFrames.set(0)
        decodedSizeMismatches.set(0)
        suspiciousFrames.set(0)
        suspiciousGreenFrames.set(0)
        suspiciousMagentaFrames.set(0)
        lastDecodedBytes.set(0)
        lastDecodeNs.set(0)
        lastRenderNs.set(0)
        observedBufferIds.clear()
        reusedBufferObjects = 0
        previousTileColors = null
        this.mode = mode
        this.availableModes = availableModes
        this.endpoints = endpoints
        this.selectedEndpoint = selectedEndpoint
        frameInterval100ns = fps.takeIf { it > 0 }?.let { 10_000_000L / it }
        expectedRawBytes = if (!isMjpeg && width > 0 && height > 0) width.toLong() * height * 2L else null
        expectedRgbxBytes = if (width > 0 && height > 0) width.toLong() * height * 4L else null
    }

    @Synchronized
    fun onDecodedRgbx(buffer: ByteBuffer, width: Int, height: Int) {
        if (!enabled) return
        val frameId = decodedFrames.incrementAndGet()
        val startNs = SystemClock.elapsedRealtimeNanos()
        val actualBytes = buffer.remaining().toLong()
        lastDecodedBytes.set(actualBytes)
        val bufferId = System.identityHashCode(buffer)
        if (!observedBufferIds.add(bufferId)) reusedBufferObjects++
        if (observedBufferIds.size > MAX_TRACKED_BUFFER_OBJECTS) observedBufferIds.remove(observedBufferIds.first())

        val inspection = DecodedRgbxInspector.inspect(buffer, width, height, previousTileColors)
        previousTileColors = inspection.tileColors
        if (inspection.sizeMismatch) {
            decodedSizeMismatches.incrementAndGet()
            logger(
                "[CORRUPT_FRAME] frame=$frameId reason=DECODED_RGBX_SIZE_MISMATCH " +
                    "bytes=${inspection.actualBytes} expected=${inspection.expectedBytes}",
            )
        }
        if (inspection.suspiciousColorBlock) {
            suspiciousFrames.incrementAndGet()
            if (inspection.greenRatio >= COLOR_RATIO_THRESHOLD) suspiciousGreenFrames.incrementAndGet()
            if (inspection.magentaRatio >= COLOR_RATIO_THRESHOLD) suspiciousMagentaFrames.incrementAndGet()
            logger(
                "[CORRUPT_FRAME] frame=$frameId reason=COLOR_BLOCK_HEURISTIC_ONLY " +
                    "bytes=${inspection.actualBytes} changed=${inspection.changedRatio.formatRatio()} " +
                    "green=${inspection.greenRatio.formatRatio()} magenta=${inspection.magentaRatio.formatRatio()}",
            )
        }
        val completeNs = SystemClock.elapsedRealtimeNanos()
        lastDecodeNs.set(completeNs)
        if (frameId <= 5 || frameId % FRAME_LOG_INTERVAL == 0L) {
            logger(
                "[UVC_DIAG] frameId=$frameId bufferObjectId=$bufferId " +
                    "diagnosticReadStart=$startNs diagnosticReadComplete=$completeNs bytes=$actualBytes",
            )
        }
    }

    fun onRendered(frameId: Long, elapsedNs: Long) {
        if (!enabled) return
        renderedFrames.incrementAndGet()
        lastRenderNs.set(elapsedNs)
        if (frameId <= 5 || frameId % FRAME_LOG_INTERVAL == 0L) {
            logger("[UVC_DIAG] renderComplete frameId=$frameId elapsedNs=$elapsedNs")
        }
    }

    @Synchronized
    fun snapshot(
        transform: CameraTransformState,
        recordingState: RecordingState,
        nativeIntegrity: NativeUvcIntegrityStats,
    ): UvcArtifactDiagnostics {
        val decoded = decodedFrames.get()
        val rendered = renderedFrames.get()
        return UvcArtifactDiagnostics(
            enabled = enabled,
            currentMode = mode,
            availableModes = availableModes,
            endpointCandidates = endpoints,
            selectedEndpoint = selectedEndpoint,
            frameInterval100ns = frameInterval100ns,
            expectedRawFrameBytes = expectedRawBytes,
            expectedDecodedRgbxBytes = expectedRgbxBytes,
            lastDecodedRgbxBytes = lastDecodedBytes.get(),
            decodedFramesObserved = decoded,
            renderedFramesObserved = rendered,
            renderBacklogEstimate = max(0, decoded - rendered),
            decodedSizeMismatches = decodedSizeMismatches.get(),
            suspiciousDecodedColorFrames = suspiciousFrames.get(),
            suspiciousGreenFrames = suspiciousGreenFrames.get(),
            suspiciousMagentaFrames = suspiciousMagentaFrames.get(),
            callbackBufferObjectsObserved = observedBufferIds.size.toLong(),
            callbackBufferObjectReuseObserved = reusedBufferObjects,
            lastDecodedFrameId = decoded,
            lastRenderedFrameId = rendered,
            lastDecodeCallbackElapsedNs = lastDecodeNs.get(),
            lastRenderCallbackElapsedNs = lastRenderNs.get(),
            transform = "rotation=${transform.rotationDegrees}, mirrorH=${transform.mirrorHorizontal}, " +
                "mirrorV=${transform.mirrorVertical}, display=${transform.displayMode}",
            recording = recordingState::class.simpleName.orEmpty(),
            nativeIntegrity = nativeIntegrity,
        )
    }

    private fun Double.formatRatio(): String = "%.3f".format(this)

    private companion object {
        const val FRAME_LOG_INTERVAL = 300L
        const val MAX_TRACKED_BUFFER_OBJECTS = 64
        const val COLOR_RATIO_THRESHOLD = 0.10
    }
}

internal data class DecodedRgbxInspection(
    val actualBytes: Int,
    val expectedBytes: Long,
    val sizeMismatch: Boolean,
    val greenRatio: Double,
    val magentaRatio: Double,
    val changedRatio: Double,
    val suspiciousColorBlock: Boolean,
    val tileColors: IntArray,
)

internal object DecodedRgbxInspector {
    private const val GRID_COLUMNS = 16
    private const val GRID_ROWS = 9
    private const val COLOR_RATIO_THRESHOLD = 0.10
    private const val CHANGED_RATIO_THRESHOLD = 0.18
    private const val CHANNEL_DOMINANCE = 55
    private const val CHANGE_DISTANCE = 210

    fun inspect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        previousTiles: IntArray?,
    ): DecodedRgbxInspection {
        val actual = buffer.remaining()
        val expected = width.toLong() * height * 4L
        val base = buffer.position()
        val tiles = IntArray(GRID_COLUMNS * GRID_ROWS) { INVALID_TILE }
        var valid = 0
        var green = 0
        var magenta = 0
        var changed = 0

        if (width > 0 && height > 0) {
            repeat(GRID_ROWS) { row ->
                val y = ((row + 0.5) * height / GRID_ROWS).toInt().coerceAtMost(height - 1)
                repeat(GRID_COLUMNS) { column ->
                    val x = ((column + 0.5) * width / GRID_COLUMNS).toInt().coerceAtMost(width - 1)
                    val offset = (y.toLong() * width + x) * 4L
                    if (offset + 2 >= actual) return@repeat
                    val r = buffer.get(base + offset.toInt()).toInt() and 0xff
                    val g = buffer.get(base + offset.toInt() + 1).toInt() and 0xff
                    val b = buffer.get(base + offset.toInt() + 2).toInt() and 0xff
                    val color = (r shl 16) or (g shl 8) or b
                    val index = row * GRID_COLUMNS + column
                    tiles[index] = color
                    valid++
                    if (g > r + CHANNEL_DOMINANCE && g > b + CHANNEL_DOMINANCE) green++
                    if (r > g + CHANNEL_DOMINANCE && b > g + CHANNEL_DOMINANCE) magenta++
                    val previous = previousTiles?.getOrNull(index) ?: INVALID_TILE
                    if (previous != INVALID_TILE && colorDistance(color, previous) >= CHANGE_DISTANCE) changed++
                }
            }
        }

        val denominator = valid.coerceAtLeast(1).toDouble()
        val greenRatio = green / denominator
        val magentaRatio = magenta / denominator
        val changedRatio = changed / denominator
        val suspicious = previousTiles != null &&
            changedRatio >= CHANGED_RATIO_THRESHOLD &&
            (greenRatio >= COLOR_RATIO_THRESHOLD || magentaRatio >= COLOR_RATIO_THRESHOLD)
        return DecodedRgbxInspection(
            actualBytes = actual,
            expectedBytes = expected,
            sizeMismatch = actual.toLong() != expected,
            greenRatio = greenRatio,
            magentaRatio = magentaRatio,
            changedRatio = changedRatio,
            suspiciousColorBlock = suspicious,
            tileColors = tiles,
        )
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(((first shr 16) and 0xff) - ((second shr 16) and 0xff)) +
            kotlin.math.abs(((first shr 8) and 0xff) - ((second shr 8) and 0xff)) +
            kotlin.math.abs((first and 0xff) - (second and 0xff))

    private const val INVALID_TILE = -1
}

private const val ARTIFACT_TAG = "RammyUvcArtifact"

internal fun artifactLog(message: String) {
    Log.d(ARTIFACT_TAG, message)
}
