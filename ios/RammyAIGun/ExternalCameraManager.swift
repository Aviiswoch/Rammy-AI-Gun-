import AVFoundation
import Combine
import Foundation
import Photos
import UIKit

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

    enum DisplayMode: String {
        case fit = "Fit"
        case fill = "Fill"
    }

    let session = AVCaptureSession()
    @Published private(set) var state: State = .searching
    @Published private(set) var rotationDegrees = 0
    @Published private(set) var displayMode: DisplayMode = .fit
    @Published private(set) var isRecording = false
    @Published private(set) var recordingStartedAt: Date?
    @Published var message: String?

    private let sessionQueue = DispatchQueue(label: "com.rammy.aigun.capture", qos: .userInitiated)
    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var activeInput: AVCaptureDeviceInput?
    private var observers: [NSObjectProtocol] = []
    private var pendingDevice: AVCaptureDevice?
    private var recordingURL: URL?
    private var mediaActionInFlight = false

    override init() {
        super.init()
        observeDevices()
        observeAppLifecycle()
        discoverExternalCamera()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        sessionQueue.sync {
            if movieOutput.isRecording { movieOutput.stopRecording() }
            if session.isRunning { session.stopRunning() }
        }
    }

    var activeDevice: AVCaptureDevice? { activeInput?.device }

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

    func takePhoto() {
        guard case .streaming = state, !mediaActionInFlight else { return }
        requestPhotosAddAccess { [weak self] granted in
            guard let self, granted else {
                self?.showMessage("Photos access is required to save the image")
                return
            }
            self.sessionQueue.async {
                guard !self.mediaActionInFlight else { return }
                self.mediaActionInFlight = true
                let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                self.applyRotation(to: self.photoOutput.connection(with: .video))
                self.photoOutput.capturePhoto(with: settings, delegate: self)
            }
        }
    }

    func toggleRecording() {
        guard case .streaming = state else { return }
        if movieOutput.isRecording {
            stopRecording()
            return
        }
        requestPhotosAddAccess { [weak self] granted in
            guard let self, granted else {
                self?.showMessage("Photos access is required to save the video")
                return
            }
            self.sessionQueue.async {
                guard !self.movieOutput.isRecording, !self.mediaActionInFlight else { return }
                self.mediaActionInFlight = true
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("RAMMY_VID_\(Self.timestamp()).mp4")
                try? FileManager.default.removeItem(at: url)
                self.recordingURL = url
                if let connection = self.movieOutput.connection(with: .video) {
                    self.applyRotation(to: connection)
                    if self.movieOutput.availableVideoCodecTypes.contains(.h264) {
                        self.movieOutput.setOutputSettings(
                            [AVVideoCodecKey: AVVideoCodecType.h264],
                            for: connection
                        )
                    }
                }
                self.movieOutput.startRecording(to: url, recordingDelegate: self)
            }
        }
    }

    func stopRecording() {
        sessionQueue.async { [weak self] in
            guard let self, self.movieOutput.isRecording else { return }
            self.movieOutput.stopRecording()
        }
    }

    func rotateClockwise() {
        rotationDegrees = (rotationDegrees + 90) % 360
        showMessage(rotationDegrees == 0 ? "Normal" : "\(rotationDegrees)°")
    }

    func toggleDisplayMode() {
        displayMode = displayMode == .fit ? .fill : .fit
        showMessage(displayMode.rawValue)
    }

    private func configure(_ device: AVCaptureDevice) {
        DispatchQueue.main.async { self.state = .connecting(device.localizedName) }
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                let input = try AVCaptureDeviceInput(device: device)
                self.session.beginConfiguration()
                self.session.inputs.forEach(self.session.removeInput)
                self.session.outputs.forEach(self.session.removeOutput)
                self.session.sessionPreset = device.supportsSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
                guard self.session.canAddInput(input) else {
                    self.session.commitConfiguration()
                    throw CaptureFailure.inputRejected
                }
                self.session.addInput(input)
                guard self.session.canAddOutput(self.photoOutput), self.session.canAddOutput(self.movieOutput) else {
                    self.session.commitConfiguration()
                    throw CaptureFailure.outputRejected
                }
                self.session.addOutput(self.photoOutput)
                self.session.addOutput(self.movieOutput)
                self.session.commitConfiguration()
                self.activeInput = input
                self.session.startRunning()
                DispatchQueue.main.async { self.state = .streaming(device.localizedName) }
            } catch {
                DispatchQueue.main.async { self.state = .failed(error.localizedDescription) }
            }
        }
    }

    private func requestPhotosAddAccess(_ completion: @escaping (Bool) -> Void) {
        switch PHPhotoLibrary.authorizationStatus(for: .addOnly) {
        case .authorized, .limited:
            completion(true)
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                completion(status == .authorized || status == .limited)
            }
        default:
            completion(false)
        }
    }

    private func saveToPhotos(
        resourceType: PHAssetResourceType,
        data: Data? = nil,
        fileURL: URL? = nil,
        completion: @escaping (Bool) -> Void
    ) {
        PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            if let data {
                request.addResource(with: resourceType, data: data, options: nil)
            } else if let fileURL {
                request.addResource(with: resourceType, fileURL: fileURL, options: nil)
            }
        } completionHandler: { saved, _ in completion(saved) }
    }

    private func applyRotation(to connection: AVCaptureConnection?) {
        guard let connection else { return }
        let angle = CGFloat(rotationDegrees)
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    private func showMessage(_ text: String) {
        DispatchQueue.main.async {
            self.message = text
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                if self.message == text { self.message = nil }
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
                self.stopRecording()
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

    private func observeAppLifecycle() {
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.stopRecording() })
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: Date())
    }

    private enum CaptureFailure: LocalizedError {
        case inputRejected
        case outputRejected

        var errorDescription: String? {
            switch self {
            case .inputRejected: "This external camera cannot be added to the capture session."
            case .outputRejected: "This external camera cannot provide photo and video outputs."
            }
        }
    }
}

extension ExternalCameraManager: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            mediaActionInFlight = false
            showMessage("Photo capture failed")
            return
        }
        saveToPhotos(resourceType: .photo, data: data) { [weak self] saved in
            DispatchQueue.main.async {
                self?.mediaActionInFlight = false
                self?.showMessage(saved ? "Photo saved" : "Photo could not be saved")
            }
        }
    }
}

extension ExternalCameraManager: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection]
    ) {
        DispatchQueue.main.async {
            self.isRecording = true
            self.recordingStartedAt = Date()
        }
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        let successfullyFinished = (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool
            ?? (error == nil)
        guard successfullyFinished else {
            try? FileManager.default.removeItem(at: outputFileURL)
            DispatchQueue.main.async {
                self.mediaActionInFlight = false
                self.isRecording = false
                self.recordingStartedAt = nil
                self.showMessage("Video recording failed")
            }
            return
        }
        saveToPhotos(resourceType: .video, fileURL: outputFileURL) { [weak self] saved in
            try? FileManager.default.removeItem(at: outputFileURL)
            DispatchQueue.main.async {
                self?.mediaActionInFlight = false
                self?.isRecording = false
                self?.recordingStartedAt = nil
                self?.recordingURL = nil
                self?.showMessage(saved ? "Video saved" : "Video could not be saved")
            }
        }
    }
}
