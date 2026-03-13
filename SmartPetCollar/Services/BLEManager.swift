import Foundation
import CoreBluetooth
import os.log

private let bleLog = OSLog(subsystem: "com.smartpetcollar.app", category: "BLE")

private func BLELog(_ message: String) {
    os_log(.info, log: bleLog, "%{public}s", message)
}

enum BLEState {
    case idle
    case scanning
    case connecting
    case connected
    case transferring
    case disconnected
}

class BLEManager: NSObject, ObservableObject {
    static let shared = BLEManager()

    private let serviceUUID = CBUUID(string: "0000FF00-0000-1000-8000-00805F9B34FB")
    private let serviceUUID16 = CBUUID(string: "00FF")
    private let scanServiceUUID = CBUUID(string: "00FF")
    private let dataCharUUID = CBUUID(string: "0000FF01-0000-1000-8000-00805F9B34FB")
    private let dataCharUUID16 = CBUUID(string: "FF01")
    private let deviceNamePrefix = "8G"

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var dataCharacteristic: CBCharacteristic?

    // 使用专用的串行队列处理 BLE 回调
    private let bleQueue = DispatchQueue(label: "com.smartpetcollar.ble", qos: .userInitiated)

    @Published var bleState: BLEState = .idle
    @Published var discoveredDevices: [CBPeripheral] = []
    private var peripheralCache: [UUID: CBPeripheral] = [:]
    // 存储广播名称（iOS 18 隐藏 Public MAC 设备的 peripheral.name）
    var advertisedNames: [UUID: String] = [:]
    // 存储 RSSI 值用于 UI 显示
    var deviceRSSI: [UUID: Int] = [:]
    @Published var transferProgress: Double = 0.0
    @Published var statusMessage: String = ""

    private var fileData: Data?
    private var fileName: String = ""
    private var currentSeq: Int = 0
    private var totalPackets: Int = 0
    private var maxPayload: Int = 500 // 根据 MTU 动态计算
    private var bytesSent: Int = 0
    private var onCompleted: ((Bool) -> Void)?
    private var waitingForAck = false
    private var isSendingStart = false

    private override init() {
        super.init()
        // 在专用队列上初始化 CBCentralManager
        centralManager = CBCentralManager(delegate: self, queue: bleQueue)
        BLELog("[BLE] BLEManager init, centralManager created on bleQueue")
    }

    func startScan() {
        BLELog("[BLE] startScan() called")
        DispatchQueue.main.async {
            self.discoveredDevices = []
            self.bleState = .scanning
            self.statusMessage = "Scanning..."
        }
        peripheralCache = [:]
        advertisedNames = [:]
        deviceRSSI = [:]

        bleQueue.async { [weak self] in
            guard let self = self else { return }
            BLELog("[BLE] startScan on bleQueue, central state: \(self.centralManager.state.rawValue)")
            guard self.centralManager.state == .poweredOn else {
                BLELog("[BLE] central not poweredOn yet, will scan in didUpdateState")
                return
            }
            self.doStartScan()
        }
    }

    private func doStartScan() {
        // retrieve 已连接的设备
        let connected = centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
        BLELog("[BLE] retrieveConnectedPeripherals: \(connected.count)")
        for p in connected {
            BLELog("[BLE] retrieved: \(p.name ?? "nil") \(p.identifier)")
            peripheralCache[p.identifier] = p
            DispatchQueue.main.async {
                if !self.discoveredDevices.contains(where: { $0.identifier == p.identifier }) {
                    self.discoveredDevices.append(p)
                }
            }
        }

        // 启动扫描（使用设备广播的 16-bit Service UUID 0x00FF 过滤）
        centralManager.scanForPeripherals(withServices: [scanServiceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        BLELog("[BLE] scanForPeripherals started with 0x00FF filter")
    }

    func stopScan() {
        centralManager.stopScan()
        DispatchQueue.main.async {
            if self.bleState == .scanning {
                self.bleState = .idle
            }
        }
    }

    func connect(to peripheral: CBPeripheral) {
        let target = peripheralCache[peripheral.identifier] ?? peripheral
        BLELog("[BLE] connect to: \(target.name ?? "nil") id: \(target.identifier) state:\(target.state.rawValue)")
        self.peripheral = target
        target.delegate = self
        DispatchQueue.main.async {
            self.bleState = .connecting
            self.statusMessage = "Connecting to \(target.name ?? "device")..."
        }
        centralManager.stopScan()
        centralManager.connect(target, options: nil)
    }

    func disconnect() {
        if let p = peripheral {
            centralManager.cancelPeripheralConnection(p)
        }
    }

    /// 获取设备显示名称（优先使用广播名，其次 peripheral.name）
    func displayName(for peripheral: CBPeripheral) -> String {
        return peripheral.name ?? advertisedNames[peripheral.identifier] ?? "Unknown"
    }

    func sendFile(data: Data, fileName: String, completion: @escaping (Bool) -> Void) {
        guard let characteristic = dataCharacteristic, let p = peripheral else {
            BLELog("[BLE] sendFile failed: no characteristic or peripheral")
            completion(false)
            return
        }
        self.fileData = data
        self.fileName = fileName

        // 根据 iOS 协商的 MTU 计算每包最大数据量
        // maximumWriteValueLength 返回的是可写净数据长度（已扣除 ATT 开销 3 字节）
        // 我们的帧格式: header(6) + packetLength(2) + data(N) + checksum(1) = N+9
        let maxWriteLen = p.maximumWriteValueLength(for: .withResponse)
        self.maxPayload = min(maxWriteLen - 9, 500) // header(6)+len(2)+checksum(1)=9
        if self.maxPayload <= 0 { self.maxPayload = 20 } // 安全下限
        BLELog("[BLE] maximumWriteValueLength=\(maxWriteLen), maxPayload=\(maxPayload)")

        self.totalPackets = BLEFileTransferProtocol.totalPackets(for: data, maxDataPerPacket: maxPayload)
        self.currentSeq = 0
        self.bytesSent = 0
        self.onCompleted = completion
        self.waitingForAck = false
        self.isSendingStart = true

        DispatchQueue.main.async {
            self.bleState = .transferring
            self.transferProgress = 0.0
            self.statusMessage = "Sending START frame..."
        }

        BLELog("[BLE] sendFile: \(fileName), size=\(data.count), totalPackets=\(totalPackets), maxPayload=\(maxPayload)")
        let startFrame = BLEFileTransferProtocol.buildStartFrame(fileName: fileName, fileData: data, maxDataPerPacket: maxPayload)
        BLELog("[BLE] START frame size=\(startFrame.count), hex=\(startFrame.prefix(20).map { String(format: "%02X", $0) }.joined(separator: " "))")
        p.writeValue(startFrame, for: characteristic, type: .withResponse)
    }

    private func sendNextDataFrame() {
        guard let fileData = fileData, let characteristic = dataCharacteristic else { return }
        currentSeq += 1
        if currentSeq > totalPackets {
            DispatchQueue.main.async {
                self.bleState = .connected
                self.transferProgress = 1.0
                self.statusMessage = "Transfer complete"
            }
            BLELog("[BLE] Transfer complete, total bytes=\(fileData.count)")
            onCompleted?(true)
            return
        }
        let frame = BLEFileTransferProtocol.buildDataFrame(fileData: fileData, seq: currentSeq, totalPackets: totalPackets, maxDataPerPacket: maxPayload)
        waitingForAck = true
        DispatchQueue.main.async {
            self.statusMessage = "Sending packet \(self.currentSeq)/\(self.totalPackets)..."
            self.transferProgress = Double(self.currentSeq) / Double(self.totalPackets)
        }
        if currentSeq <= 3 || currentSeq == totalPackets {
            BLELog("[BLE] DATA frame seq=\(currentSeq)/\(totalPackets), size=\(frame.count)")
        }
        peripheral?.writeValue(frame, for: characteristic, type: .withResponse)
    }
}

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        BLELog("[BLE] centralManagerDidUpdateState: \(central.state.rawValue)")
        if central.state == .poweredOn && bleState == .scanning {
            BLELog("[BLE] poweredOn while scanning, starting scan now")
            doStartScan()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = peripheral.name ?? advName ?? "Unknown"
        BLELog("[BLE] discovered: \"\(name)\" id=\(peripheral.identifier) rssi=\(RSSI)")
        BLELog("[BLE] advertisementData: \(advertisementData)")
        peripheralCache[peripheral.identifier] = peripheral
        if let advName = advName {
            advertisedNames[peripheral.identifier] = advName
        }
        deviceRSSI[peripheral.identifier] = RSSI.intValue
        DispatchQueue.main.async {
            if !self.discoveredDevices.contains(where: { $0.identifier == peripheral.identifier }) {
                self.discoveredDevices.append(peripheral)
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        BLELog("[BLE] didConnect: \(peripheral.name ?? "nil")")
        DispatchQueue.main.async {
            self.statusMessage = "Connected, discovering services..."
        }
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        BLELog("[BLE] didFailToConnect: \(error?.localizedDescription ?? "nil")")
        DispatchQueue.main.async {
            self.bleState = .disconnected
            self.statusMessage = "Connection failed: \(error?.localizedDescription ?? "unknown")"
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        BLELog("[BLE] didDisconnect: \(error?.localizedDescription ?? "clean")")
        DispatchQueue.main.async {
            self.bleState = .disconnected
            self.dataCharacteristic = nil
            self.statusMessage = "Disconnected"
        }
    }
}

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            BLELog("[BLE] didDiscoverServices error: \(error)")
            DispatchQueue.main.async { self.statusMessage = "Service discovery error: \(error.localizedDescription)" }
            return
        }
        guard let services = peripheral.services else { return }
        let allUUIDs = services.map { $0.uuid.uuidString }
        BLELog("[BLE] didDiscoverServices: \(allUUIDs.joined(separator: ", "))")

        if let targetService = services.first(where: { $0.uuid == serviceUUID || $0.uuid == serviceUUID16 }) {
            BLELog("[BLE] target service found (uuid=\(targetService.uuid)), discovering characteristics")
            peripheral.discoverCharacteristics(nil, for: targetService)
        } else {
            BLELog("[BLE] target service NOT found. All: \(allUUIDs.joined(separator: ", "))")
            DispatchQueue.main.async {
                self.statusMessage = "Target service not found. Found: \(allUUIDs.joined(separator: ", "))"
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            BLELog("[BLE] didDiscoverCharacteristics error: \(error)")
            DispatchQueue.main.async { self.statusMessage = "Characteristic error: \(error.localizedDescription)" }
            return
        }
        guard let characteristics = service.characteristics else { return }
        let allUUIDs = characteristics.map { $0.uuid.uuidString }
        BLELog("[BLE] didDiscoverCharacteristics for \(service.uuid): \(allUUIDs.joined(separator: ", "))")

        if let targetChar = characteristics.first(where: { $0.uuid == dataCharUUID || $0.uuid == dataCharUUID16 }) {
            BLELog("[BLE] target characteristic found, ready!")
            self.dataCharacteristic = targetChar
            DispatchQueue.main.async {
                self.bleState = .connected
                self.statusMessage = "Ready"
            }
        } else {
            BLELog("[BLE] target char NOT found. All: \(allUUIDs.joined(separator: ", "))")
            DispatchQueue.main.async {
                self.statusMessage = "Target char not found. Found: \(allUUIDs.joined(separator: ", "))"
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            BLELog("[BLE] didWriteValue error: \(error)")
            DispatchQueue.main.async {
                self.statusMessage = "Write error: \(error.localizedDescription)"
                self.bleState = .connected
                self.onCompleted?(false)
            }
            return
        }

        if isSendingStart {
            BLELog("[BLE] START frame ACK received")
            isSendingStart = false
            bleQueue.asyncAfter(deadline: .now() + 0.01) {
                self.sendNextDataFrame()
            }
        } else {
            waitingForAck = false
            bleQueue.asyncAfter(deadline: .now() + 0.002) {
                self.sendNextDataFrame()
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {}
}
