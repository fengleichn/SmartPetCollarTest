import SwiftUI
import PhotosUI
import os.log

private let rawLog = OSLog(subsystem: "com.smartpetcollar.app", category: "App")

struct BLERawImageView: View {
    @ObservedObject private var ble = BLEManager.shared
    @State private var selectedImageData: Data?
    @State private var selectedUIImage: UIImage?
    @State private var showImagePicker = false
    @State private var showDeviceList = false
    @State private var isSending = false

    private let deviceFileName = "/sdcard/user/1.jpg"

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    bleStatusCard
                    imageCard
                    actionButtons
                    progressCard
                }
                .padding()
            }
            .navigationTitle("BLE Raw Image")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showImagePicker) {
                ImagePickerView(image: $selectedUIImage, onPicked: { img in
                    selectedUIImage = img
                    selectedImageData = img.jpegData(compressionQuality: 1.0)
                })
            }
            .sheet(isPresented: $showDeviceList) {
                DeviceListView(bleManager: ble)
            }
        }
    }

    private var bleStatusCard: some View {
        HStack {
            Circle()
                .fill(bleStatusColor)
                .frame(width: 10, height: 10)
            Text(bleStatusText)
                .font(.subheadline)
            Spacer()
            Button(ble.bleState == .connected ? "Disconnect" : "Scan") {
                if ble.bleState == .connected {
                    ble.disconnect()
                } else {
                    showDeviceList = true
                    ble.startScan()
                }
            }
            .buttonStyle(.bordered)
            .font(.caption)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
    }

    private var bleStatusColor: Color {
        switch ble.bleState {
        case .connected: return .green
        case .scanning, .connecting: return .orange
        case .transferring: return .blue
        default: return .red
        }
    }

    private var bleStatusText: String {
        switch ble.bleState {
        case .idle: return "BLE Idle"
        case .scanning: return "Scanning..."
        case .connecting: return "Connecting..."
        case .connected: return "Connected"
        case .transferring: return "Transferring..."
        case .disconnected: return "Disconnected"
        }
    }

    private var imageCard: some View {
        VStack(spacing: 8) {
            if let img = selectedUIImage {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                    .frame(height: 250)
                    .cornerRadius(10)
                if let data = selectedImageData {
                    Text("Raw file: \(data.count) bytes")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            } else {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color(.tertiarySystemBackground))
                    .frame(height: 200)
                    .overlay(
                        VStack {
                            Image(systemName: "doc.fill")
                                .font(.largeTitle)
                                .foregroundColor(.secondary)
                            Text("No image selected")
                                .foregroundColor(.secondary)
                        }
                    )
            }
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            Button("Pick Image") {
                showImagePicker = true
            }
            .buttonStyle(.bordered)

            Button("Send Raw") {
                sendRaw()
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImageData == nil || ble.bleState != .connected || isSending)
        }
    }

    private var progressCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            if ble.bleState == .transferring {
                ProgressView(value: ble.transferProgress)
                    .progressViewStyle(.linear)
            }
            Text(ble.statusMessage)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
    }

    private func sendRaw() {
        guard let data = selectedImageData else { return }
        // Debug: 保存即将发送的数据到 Documents 目录
        saveDebugFile(data: data, name: "debug_raw.jpg")
        isSending = true
        ble.sendFile(data: data, fileName: deviceFileName) { success in
            isSending = false
        }
    }

    private func saveDebugFile(data: Data, name: String) {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let url = docs.appendingPathComponent(name)
        try? data.write(to: url)
        os_log(.info, log: rawLog, "%{public}s", "[DEBUG] Saved \(data.count) bytes to \(url.path)")
    }
}
