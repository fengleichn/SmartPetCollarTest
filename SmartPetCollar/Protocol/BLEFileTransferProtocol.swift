import Foundation

struct BLEFileTransferProtocol {
    static let defaultMaxDataPerPacket = 500
    static let sessionId: UInt8 = 0x01
    static let startFrameType: UInt8 = 0x03
    static let dataFrameType: UInt8 = 0x00

    /// Build START frame
    static func buildStartFrame(fileName: String, fileData: Data, maxDataPerPacket: Int = defaultMaxDataPerPacket) -> Data {
        let fileNameBytes = fileNamePadded(fileName)
        let totalPackets = UInt16((fileData.count + maxDataPerPacket - 1) / maxDataPerPacket)

        var frame = Data()

        // Header: sessionId(1) + frameType(1) + totalPackets+1(2 big-endian) + 0x00 0x00(2)
        frame.append(sessionId)
        frame.append(startFrameType)
        let totalPlusOne = totalPackets + 1
        frame.append(UInt8((totalPlusOne >> 8) & 0xFF))
        frame.append(UInt8(totalPlusOne & 0xFF))
        frame.append(0x00)
        frame.append(0x00)

        // fileInfoLen = 1 (fileNameLength field) + 32 (padded name) + 4 (fileLength) = 37
        let fileInfoLen: UInt16 = 37
        frame.append(UInt8((fileInfoLen >> 8) & 0xFF))
        frame.append(UInt8(fileInfoLen & 0xFF))

        // fileNameLength
        let originalNameData = fileName.data(using: .utf8) ?? Data()
        frame.append(UInt8(min(originalNameData.count, 255)))

        // fileName padded to 32 bytes
        frame.append(contentsOf: fileNameBytes)

        // fileLength (4 bytes, big-endian)
        let fileLength = UInt32(fileData.count)
        frame.append(UInt8((fileLength >> 24) & 0xFF))
        frame.append(UInt8((fileLength >> 16) & 0xFF))
        frame.append(UInt8((fileLength >> 8) & 0xFF))
        frame.append(UInt8(fileLength & 0xFF))

        // Checksum: 256 - (sum of all bytes) % 256
        frame.append(checksum(for: frame))

        return frame
    }

    /// Build DATA frame for given sequence (1-based)
    static func buildDataFrame(fileData: Data, seq: Int, totalPackets: Int, maxDataPerPacket: Int = defaultMaxDataPerPacket) -> Data {
        let offset = (seq - 1) * maxDataPerPacket
        let end = min(offset + maxDataPerPacket, fileData.count)
        let chunk = fileData[offset..<end]

        var frame = Data()

        // Header: sessionId(1) + frameType(1) + totalPackets+1(2 big-endian) + seq(2 big-endian)
        frame.append(sessionId)
        frame.append(dataFrameType)
        let totalPlusOne = UInt16(totalPackets + 1)
        frame.append(UInt8((totalPlusOne >> 8) & 0xFF))
        frame.append(UInt8(totalPlusOne & 0xFF))
        let seqU16 = UInt16(seq)
        frame.append(UInt8((seqU16 >> 8) & 0xFF))
        frame.append(UInt8(seqU16 & 0xFF))

        // dataLength (2 bytes, big-endian)
        let dataLen = UInt16(chunk.count)
        frame.append(UInt8((dataLen >> 8) & 0xFF))
        frame.append(UInt8(dataLen & 0xFF))

        // data
        frame.append(contentsOf: chunk)

        // Checksum
        frame.append(checksum(for: frame))

        return frame
    }

    static func totalPackets(for fileData: Data, maxDataPerPacket: Int = defaultMaxDataPerPacket) -> Int {
        (fileData.count + maxDataPerPacket - 1) / maxDataPerPacket
    }

    private static func fileNamePadded(_ name: String) -> [UInt8] {
        let bytes = Array((name.data(using: .utf8) ?? Data()))
        var padded = [UInt8](repeating: 0, count: 32)
        let copyCount = min(bytes.count, 32)
        for i in 0..<copyCount {
            padded[i] = bytes[i]
        }
        return padded
    }

    private static func checksum(for data: Data) -> UInt8 {
        let sum = data.reduce(0) { Int($0) + Int($1) }
        let remainder = sum % 256
        return remainder == 0 ? 0 : UInt8(256 - remainder)
    }
}
