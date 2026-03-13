import Foundation

struct LocationData: Codable {
    let type: String?
    let id: String?
    let act: String?
    let dur: Int?
    let data: GnssData?
}

struct GnssData: Codable {
    let lat: Double?
    let lon: Double?
    let sat: Int?

    var isValidGnssFix: Bool {
        (sat ?? 0) > 3
    }
}
