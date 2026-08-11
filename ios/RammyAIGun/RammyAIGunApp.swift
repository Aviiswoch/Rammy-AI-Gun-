import SwiftUI

@main
struct RammyAIGunApp: App {
    @StateObject private var camera = ExternalCameraManager()

    var body: some Scene {
        WindowGroup {
            CameraMonitorView(camera: camera)
                .preferredColorScheme(.dark)
        }
    }
}

