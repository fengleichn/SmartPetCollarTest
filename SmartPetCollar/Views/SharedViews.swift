import SwiftUI
import PhotosUI
import TOCropViewController

struct ImagePickerView: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    var onPicked: ((UIImage) -> Void)?
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.selectionLimit = 1
        config.filter = .images
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: ImagePickerView

        init(_ parent: ImagePickerView) {
            self.parent = parent
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let result = results.first else { return }
            result.itemProvider.loadObject(ofClass: UIImage.self) { object, _ in
                if let image = object as? UIImage {
                    DispatchQueue.main.async {
                        self.parent.image = image
                        self.parent.onPicked?(image)
                    }
                }
            }
        }
    }
}

class TOCropViewHelper: NSObject, TOCropViewControllerDelegate {
    static let shared = TOCropViewHelper()

    var targetSize: CGSize = CGSize(width: 466, height: 466)
    var onCrop: ((UIImage) -> Void)?

    func showCropper(image: UIImage, targetSize: CGSize, onCrop: @escaping (UIImage) -> Void) {
        self.targetSize = targetSize
        self.onCrop = onCrop

        let cropVC = TOCropViewController(image: image)
        cropVC.aspectRatioPreset = .presetSquare
        cropVC.aspectRatioLockEnabled = true
        cropVC.resetAspectRatioEnabled = false
        cropVC.aspectRatioPickerButtonHidden = true
        cropVC.delegate = self

        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = scene.windows.first?.rootViewController else { return }

        // Find the topmost presented VC
        var topVC = rootVC
        while let presented = topVC.presentedViewController {
            topVC = presented
        }
        topVC.present(cropVC, animated: true)
    }

    func cropViewController(_ cropViewController: TOCropViewController, didCropTo image: UIImage, with cropRect: CGRect, angle: Int) {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        cropViewController.dismiss(animated: true) {
            self.onCrop?(resized)
            self.onCrop = nil
        }
    }

    func cropViewController(_ cropViewController: TOCropViewController, didFinishCancelled cancelled: Bool) {
        cropViewController.dismiss(animated: true) {
            self.onCrop = nil
        }
    }
}

struct DeviceListView: View {
    @ObservedObject var bleManager: BLEManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                if bleManager.discoveredDevices.isEmpty {
                    HStack {
                        ProgressView()
                        Text("Scanning for devices with prefix \"8G\"...")
                            .foregroundColor(.secondary)
                            .padding(.leading, 8)
                    }
                } else {
                    ForEach(bleManager.discoveredDevices, id: \.identifier) { device in
                        Button {
                            bleManager.connect(to: device)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                HStack {
                                    Text(bleManager.displayName(for: device))
                                        .font(.headline)
                                    Spacer()
                                    if let rssi = bleManager.deviceRSSI[device.identifier] {
                                        Text("\(rssi) dBm")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }
                                Text(device.identifier.uuidString)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .foregroundColor(.primary)
                    }
                }
            }
            .navigationTitle("Select Device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        bleManager.stopScan()
                        dismiss()
                    }
                }
            }
        }
    }
}
