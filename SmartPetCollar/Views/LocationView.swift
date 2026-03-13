import SwiftUI
import MapKit

struct LocationView: View {
    @StateObject private var mqtt = MQTTManager.shared
    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 39.9042, longitude: 116.4074),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )
    @State private var pinCoordinate: CLLocationCoordinate2D?
    @State private var rawMessage: String = ""

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Map
                Map(coordinateRegion: $region, annotationItems: pinItems) { item in
                    MapPin(coordinate: item.coordinate, tint: .red)
                }
                .frame(maxHeight: .infinity)

                Divider()

                // Info panel
                VStack(spacing: 12) {
                    statusRow
                    locationRow
                    rawMessageRow
                    buttonRow
                }
                .padding()
                .background(Color(.systemBackground))
            }
            .navigationTitle("Location Monitor")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            mqtt.onMessageReceived = { data in
                handleLocationData(data)
            }
        }
        .onDisappear {
            mqtt.disconnect()
        }
    }

    private var pinItems: [IdentifiableCoordinate] {
        guard let coord = pinCoordinate else { return [] }
        return [IdentifiableCoordinate(coordinate: coord)]
    }

    private var statusRow: some View {
        HStack {
            Circle()
                .fill(mqtt.isConnected ? Color.green : Color.red)
                .frame(width: 10, height: 10)
            Text(mqtt.isConnected ? "Connected" : "Disconnected")
                .font(.subheadline)
            Spacer()
        }
    }

    private var locationRow: some View {
        Group {
            if let loc = mqtt.receivedLocationData, let gnss = loc.data {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Lat: \(gnss.lat ?? 0, specifier: "%.6f")")
                            .font(.caption)
                        Text("Lon: \(gnss.lon ?? 0, specifier: "%.6f")")
                            .font(.caption)
                        Text("Satellites: \(gnss.sat ?? 0) | Fix: \(gnss.isValidGnssFix ? "✓" : "✗")")
                            .font(.caption)
                            .foregroundColor(gnss.isValidGnssFix ? .green : .orange)
                    }
                    Spacer()
                }
            } else {
                Text("No location data yet")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var rawMessageRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Last Message:")
                .font(.caption2)
                .foregroundColor(.secondary)
            Text(mqtt.lastMessage.isEmpty ? "(none)" : mqtt.lastMessage)
                .font(.caption2)
                .foregroundColor(.primary)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var buttonRow: some View {
        HStack(spacing: 12) {
            Button(mqtt.isConnected ? "Disconnect" : "Connect") {
                if mqtt.isConnected {
                    mqtt.disconnect()
                } else {
                    mqtt.connect()
                }
            }
            .buttonStyle(.bordered)

            Button("Screen On") {
                mqtt.sendScreenOn()
            }
            .buttonStyle(.borderedProminent)
            .disabled(!mqtt.isConnected)

            Button("Screen Off") {
                mqtt.sendScreenOff()
            }
            .buttonStyle(.bordered)
            .disabled(!mqtt.isConnected)
        }
    }

    private func handleLocationData(_ data: LocationData) {
        guard let gnss = data.data,
              let lat = gnss.lat,
              let lon = gnss.lon else { return }
        let coord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        pinCoordinate = coord
        withAnimation {
            region.center = coord
        }
    }
}

struct IdentifiableCoordinate: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}
