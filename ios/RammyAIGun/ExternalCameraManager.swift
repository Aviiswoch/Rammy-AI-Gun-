import AVFoundation
import Combine
import Foundation

final class ExternalCameraManager: NSObject, ObservableObject {
    enum State: Equatable {
        case searching
        case permissionRequired
        case connecting(String)
        case streaming(String)
        case unavailable
        case denied
        case failed(String)
    }

    let session = AVCaptureSession()
    @Published private(set) var state: State = .searching

    private let sessionQueue = DispatchQueue(label: "com.rammy.aigun.capture", qos: .userInitiated)
    private var activeInput: AVCaptureDeviceInput?
    private var observers: [NSObjectProtocol] = []
    private var pendingDevice: AVCaptureDevice?

    override init() {
        super.init()
        observeDevices()
        discoverExternalCamera()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        sessionQueue.sync {
            if session.isRunning { session.stopRunning() }
        }
    }

    func requestAccessAndConnect() {
        guard let device = pendingDevice else {
            discoverExternalCamera()
            return
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure(device)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted { self.configure(device) }
                else { DispatchQueue.main.async { self.state = .denied } }
            }
        default:
            state = .denied
        }
    }

    func discoverExternalCamera() {
        guard #available(iOS 17.0, *) else {
            state = .unavailable
            return
        }

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external],
            mediaType: .video,
            position: .unspecified
        )
        guard let device = discovery.devices.first(where: \.isConnected) else {
            pendingDevice = nil
            state = .unavailable
            return
        }

        pendingDevice = device
        if AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
            configure(device)
        } else {
            state = .permissionRequired
        }
    }

    private func configure(_ device: AVCaptureDevice) {
        DispatchQueue.main.async { self.state = .connecting(device.localizedName) }
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                let input = try AVCaptureDeviceInput(device: device)
                self.session.beginConfiguration()
                self.session.inputs.forEach(self.session.removeInput)
                self.session.sessionPreset = device.supportsSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
                guard self.session.canAddInput(input) else {
                    self.session.commitConfiguration()
                    throw CaptureFailure.inputRejected
                }
                self.session.addInput(input)
                self.session.commitConfiguration()
                self.activeInput = input
                self.session.startRunning()
                DispatchQueue.main.async { self.state = .streaming(device.localizedName) }
            } catch {
                DispatchQueue.main.async { self.state = .failed(error.localizedDescription) }
            }
        }
    }

    private func observeDevices() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVCaptureDevice.wasConnectedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.discoverExternalCamera() })
        observers.append(center.addObserver(
            forName: AVCaptureDevice.wasDisconnectedNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self, let device = notification.object as? AVCaptureDevice else { return }
            if device.uniqueID == self.activeInput?.device.uniqueID {
                self.sessionQueue.async {
                    if self.session.isRunning { self.session.stopRunning() }
                    DispatchQueue.main.async {
                        self.activeInput = nil
                        self.pendingDevice = nil
                        self.state = .unavailable
                    }
                }
            }
        })
    }

    private enum CaptureFailure: LocalizedError {
        case inputRejected

        var errorDescription: String? {
            "This external camera cannot be added to the capture session."
        }
    }
}
