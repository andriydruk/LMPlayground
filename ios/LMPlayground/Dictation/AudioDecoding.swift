import AVFoundation

/// Decodes audio into what Parakeet expects: 16 kHz mono Float32.
enum AudioDecoding {

    static let sampleRate: Double = 16_000

    static var targetFormat: AVAudioFormat {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false)!
    }

    /// Read a whole audio file (WAV, M4A, ...) and resample it.
    static func samples(contentsOf url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        guard let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                           frameCapacity: AVAudioFrameCount(file.length)) else {
            return []
        }
        try file.read(into: input)
        return try convert(input)
    }

    /// Resample a buffer in any PCM format.
    static func convert(_ input: AVAudioPCMBuffer) throws -> [Float] {
        let output = targetFormat
        guard let converter = AVAudioConverter(from: input.format, to: output) else { return [] }
        let ratio = output.sampleRate / input.format.sampleRate
        let capacity = AVAudioFrameCount(Double(input.frameLength) * ratio) + 1024
        guard let buffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else { return [] }

        nonisolated(unsafe) var consumed = false
        var error: NSError?
        converter.convert(to: buffer, error: &error) { _, status in
            if consumed {
                status.pointee = .endOfStream
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return input
        }
        if let error { throw error }
        guard let channel = buffer.floatChannelData?[0] else { return [] }
        return Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
    }
}
