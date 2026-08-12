import SwiftUI

struct CameraMonitorView: View {
    @ObservedObject var camera: ExternalCameraManager

    @State private var controlsVisible = true
    @State private var showingInfo = false
    @State private var showingSettings = false

    private let cyan = Color(red: 0.36, green: 0.89, blue: 1.0)

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(
                session: camera.session,
                rotationDegrees: camera.rotationDegrees,
                displayMode: camera.displayMode
            )
            .ignoresSafeArea()

            if !isStreaming {
                Color.black.opacity(0.88).ignoresSafeArea()
                statePanel
            }

            if controlsVisible || !isStreaming {
                monitorChrome
            } else {
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .onTapGesture { controlsVisible = true }
            }

            if let message = camera.message {
                glass { Text(message).font(.caption) }
                    .transition(.opacity)
            }

            if showingInfo { informationOverlay }
            if showingSettings { settingsOverlay }
        }
        .animation(.easeOut(duration: 0.15), value: controlsVisible)
        .animation(.easeOut(duration: 0.15), value: camera.message)
    }

    private var monitorChrome: some View {
        VStack {
            HStack {
                glass {
                    HStack(spacing: 8) {
                        Circle().fill(isStreaming ? cyan : .gray).frame(width: 7, height: 7)
                        Text("RAMMY AI GUN").font(.caption2.weight(.semibold)).tracking(1.5)
                    }
                }
                Spacer()
                if isStreaming {
                    glass {
                        HStack(spacing: 18) {
                            tool("info.circle") { showingInfo = true }
                            tool("rotate.right", action: camera.rotateClockwise)
                            tool("aspectratio", action: camera.toggleDisplayMode)
                            tool("arrow.up.left.and.arrow.down.right") { controlsVisible = false }
                            tool("eye.slash") { controlsVisible = false }
                            tool("gearshape.fill") { showingSettings = true }
                        }
                    }
                }
            }
            Spacer()
            if isStreaming { bottomControls }
        }
        .padding(12)
    }

    private var isStreaming: Bool {
        if case .streaming = camera.state { return true }
        return false
    }

    @ViewBuilder private var statePanel: some View {
        let presentation = statePresentation
        VStack(spacing: 18) {
            RoundedRectangle(cornerRadius: 24)
                .fill(cyan.opacity(0.10))
                .frame(width: 92, height: 92)
                .overlay(Image(systemName: "cable.connector").font(.system(size: 36)).foregroundStyle(cyan))
            Text(presentation.title).font(.title2.weight(.medium)).multilineTextAlignment(.center)
            Text(presentation.detail)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.58))
                .multilineTextAlignment(.center)
            if presentation.canConnect {
                Button("Allow & Connect", action: camera.requestAccessAndConnect)
                    .buttonStyle(.borderedProminent)
                    .tint(cyan)
                    .foregroundStyle(.black)
            }
        }
        .padding(32)
    }

    private var statePresentation: (title: String, detail: String, canConnect: Bool) {
        switch camera.state {
        case .searching:
            ("Looking for camera", "Connect a compatible external camera.", false)
        case .permissionRequired:
            ("External camera detected", "Camera access is required to display the connected camera.", true)
        case .connecting(let name):
            ("Connecting camera...", name, false)
        case .streaming(let name):
            (name, "Live", false)
        case .unavailable:
            ("External USB camera is not available on this device.", "Rammy AI Gun only uses cameras exposed by iOS or iPadOS through AVFoundation.", false)
        case .denied:
            ("Camera access is required", "Allow camera access in Settings to use an exposed external camera.", false)
        case .failed(let message):
            ("Camera could not start", message, true)
        }
    }

    private var bottomControls: some View {
        glass {
            HStack {
                control("photo.on.rectangle", "Gallery", enabled: false) {}
                Spacer()
                control("camera.fill", "Photo", action: camera.takePhoto)
                Spacer()
                if camera.isRecording {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        control("stop.fill", elapsed(at: context.date), red: true, action: camera.toggleRecording)
                    }
                } else {
                    control("record.circle", "Record", red: true, action: camera.toggleRecording)
                }
                Spacer()
                control("gearshape.fill", "Settings") { showingSettings = true }
            }
        }
    }

    private var informationOverlay: some View {
        overlay(title: "Camera information", dismiss: { showingInfo = false }) {
            informationLine("Camera connected", isStreaming ? "Yes" : "No")
            informationLine("Camera", camera.activeDevice?.localizedName ?? "Not connected")
            informationLine("Resolution", activeResolution)
            informationLine("FPS", activeFPS)
            informationLine("Rotation", "\(camera.rotationDegrees)°")
            informationLine("Display", camera.displayMode.rawValue)
            informationLine("Recording", camera.isRecording ? "Recording" : "Idle")
            informationLine("App", "Rammy AI Gun")
            informationLine("Version", Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown")
        }
    }

    private var settingsOverlay: some View {
        overlay(title: "Settings", dismiss: { showingSettings = false }) {
            informationLine("External camera", camera.activeDevice?.localizedName ?? "Not connected")
            informationLine("Display mode", camera.displayMode.rawValue)
            informationLine("Camera rotation", "\(camera.rotationDegrees)°")
            informationLine("Photos", "Photos / Rammy AI Gun")
            informationLine("Videos", "Photos / Rammy AI Gun")
        }
    }

    private var activeResolution: String {
        guard let device = camera.activeDevice else { return "N/A" }
        let dimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        return "\(dimensions.width) × \(dimensions.height)"
    }

    private var activeFPS: String {
        guard let device = camera.activeDevice else { return "N/A" }
        let duration = device.activeVideoMinFrameDuration
        guard duration.seconds > 0 else { return "N/A" }
        return String(format: "%.0f", 1 / duration.seconds)
    }

    private func elapsed(at date: Date) -> String {
        let seconds = Int(date.timeIntervalSince(camera.recordingStartedAt ?? date))
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private func tool(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).foregroundStyle(.white)
        }
        .buttonStyle(.plain)
    }

    private func control(
        _ icon: String,
        _ label: String,
        red: Bool = false,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3).foregroundStyle(red ? .red : .white)
                Text(label).font(.system(size: 9)).foregroundStyle(.white)
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.48)
    }

    private func overlay<Content: View>(
        title: String,
        dismiss: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) -> some View {
        ZStack {
            Color.black.opacity(0.42).ignoresSafeArea().onTapGesture(perform: dismiss)
            glass {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(title).font(.headline)
                        Spacer()
                        Button(action: dismiss) { Image(systemName: "xmark") }.buttonStyle(.plain)
                    }
                    content()
                }
                .frame(maxWidth: 360)
            }
        }
    }

    private func informationLine(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.white.opacity(0.58))
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
        .font(.caption)
    }

    private func glass<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial.opacity(0.76), in: RoundedRectangle(cornerRadius: 16))
    }
}
