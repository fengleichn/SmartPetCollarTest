import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            LocationView()
                .tabItem {
                    Label("Location", systemImage: "location.fill")
                }

            BLEImageView()
                .tabItem {
                    Label("BLE Image", systemImage: "photo.fill")
                }

            BLERawImageView()
                .tabItem {
                    Label("Raw Image", systemImage: "doc.fill")
                }
        }
    }
}
