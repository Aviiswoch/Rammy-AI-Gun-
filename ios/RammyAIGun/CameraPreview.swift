import AVFoundation
import SwiftUI

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let rotationDegrees: Int
    let displayMode: ExternalCameraManager.DisplayMode

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        apply(to: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.previewLayer.session = session
        apply(to: uiView)
    }

    private func apply(to view: PreviewView) {
        view.previewLayer.videoGravity = displayMode == .fit ? .resizeAspect : .resizeAspectFill
        guard let connection = view.previewLayer.connection else { return }
        let angle = CGFloat(rotationDegrees)
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }
}

final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
