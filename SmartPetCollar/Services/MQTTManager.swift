import Foundation
import CocoaMQTT

class MQTTManager: NSObject, ObservableObject {
    static let shared = MQTTManager()

    private let broker = "47.100.220.100"
    private let port: UInt16 = 1883
    private let deviceMac = "E006F4"

    private var publishTopic: String { "v175/\(deviceMac)/app" }
    private var subscribeTopic: String { "v175/\(deviceMac)/dev" }

    private var mqtt: CocoaMQTT?

    @Published var isConnected = false
    @Published var lastMessage: String = ""
    @Published var receivedLocationData: LocationData?

    var onMessageReceived: ((LocationData) -> Void)?

    private override init() {
        super.init()
    }

    func connect() {
        let clientID = "iOS-SmartPetCollar-\(Int.random(in: 1000...9999))"
        mqtt = CocoaMQTT(clientID: clientID, host: broker, port: port)
        mqtt?.delegate = self
        mqtt?.keepAlive = 60
        mqtt?.autoReconnect = true
        _ = mqtt?.connect()
    }

    func disconnect() {
        mqtt?.disconnect()
    }

    func sendScreenOn(duration: Int = 60) {
        let payload = #"{"id":"001","act":"so","dur":\#(duration)}"#
        publish(message: payload)
    }

    func sendScreenOff() {
        let payload = #"{"id":"001","act":"st","dur":0}"#
        publish(message: payload)
    }

    private func publish(message: String) {
        let msg = CocoaMQTTMessage(topic: publishTopic, string: message, qos: .qos1, retained: true)
        mqtt?.publish(msg)
    }

    private func subscribe() {
        mqtt?.subscribe(subscribeTopic, qos: .qos1)
    }
}

extension MQTTManager: CocoaMQTTDelegate {
    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        if ack == .accept {
            DispatchQueue.main.async {
                self.isConnected = true
            }
            subscribe()
        }
    }

    func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {}

    func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {}

    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        guard let payload = message.string else { return }
        DispatchQueue.main.async {
            self.lastMessage = payload
        }
        if let data = payload.data(using: .utf8),
           let locationData = try? JSONDecoder().decode(LocationData.self, from: data) {
            DispatchQueue.main.async {
                self.receivedLocationData = locationData
                self.onMessageReceived?(locationData)
            }
        }
    }

    func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {}

    func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {}

    func mqttDidPing(_ mqtt: CocoaMQTT) {}

    func mqttDidReceivePong(_ mqtt: CocoaMQTT) {}

    func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
        DispatchQueue.main.async {
            self.isConnected = false
        }
    }
}
