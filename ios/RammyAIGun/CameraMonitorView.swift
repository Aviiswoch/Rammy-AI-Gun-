import SwiftUI

struct CameraMonitorView: View {
    @ObservedObject var camera: ExternalCameraManager

    private let cyan = Color(red: 0.36, green: 0.89, blue: 1.0)

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session).ignoresSafeArea()

            if !isStreaming {
                Color.black.opacity(0.88).ignoresSafeArea()
                statePanel
            }

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
                                Image(systemName: "info.circle")
                                Image(systemName: "rotate.right")
                                Image(systemName: "aspectratio")
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                            }.foregroundStyle(.white.opacity(0.72))
                        }
                    }
                }
                Spacer()
                if isStreaming { disabledControls }
            }
            .padding(12)
        }
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
            ("Connecting camera…", name, false)
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

    private var disabledControls: some View {
        glass {
            HStack {
                control("photo.on.rectangle", "Gallery")
                Spacer()
                control("camera.fill", "Photo")
                Spacer()
                control("record.circle", "Record", red: true)
                Spacer()
                control("gearshape.fill", "Settings")
            }.opacity(0.48)
        }
    }

    private func control(_ icon: String, _ label: String, red: Bool = false) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.title3).foregroundStyle(red ? .red : .white)
            Text(label).font(.system(size: 9))
        }
    }

    private func glass<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial.opacity(0.76), in: RoundedRectangle(cornerRadius: 16))
    }
}

