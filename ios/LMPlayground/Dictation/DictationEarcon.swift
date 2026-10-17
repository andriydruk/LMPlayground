import AVFoundation

/// The short tones that mark dictation starting and stopping (Android:
/// DictationEarcon): a soft rising two-note blip when recording begins, the
/// same notes falling when it stops. Synthesized, so there's no asset.
/// Best-effort: an audio failure is ignored.
@MainActor
enum DictationEarcon {

    private static let sampleRate = 44_100
    private static let noteMilliseconds = 70
    private static let volume: Float = 0.22

    private static let startPlayer = player(twoNotes(660, 880))
    private static let stopPlayer = player(twoNotes(880, 660))

    static func playStart() { play(startPlayer) }
    static func playStop() { play(stopPlayer) }

    private static func play(_ player: AVAudioPlayer?) {
        guard let player else { return }
        player.currentTime = 0
        player.play()
    }

    private static func player(_ wav: Data) -> AVAudioPlayer? {
        let player = try? AVAudioPlayer(data: wav)
        player?.prepareToPlay()
        return player
    }

    /// Two sine notes back to back, each with a short fade in and out, as a
    /// 16-bit mono WAV.
    static func twoNotes(_ first: Double, _ second: Double) -> Data {
        let perNote = sampleRate * noteMilliseconds / 1000
        let fade = Float(sampleRate * 8 / 1000)
        var pcm = [Int16]()
        pcm.reserveCapacity(perNote * 2)
        for frequency in [first, second] {
            for i in 0..<perNote {
                let envelope = min(1, Float(min(i, perNote - 1 - i)) / fade)
                let sample = Float(sin(2 * .pi * frequency * Double(i) / Double(sampleRate))) * volume * envelope
                pcm.append(Int16(sample * Float(Int16.max)))
            }
        }
        return wav(pcm)
    }

    private static func wav(_ pcm: [Int16]) -> Data {
        func le32(_ v: Int) -> [UInt8] { (0..<4).map { UInt8((v >> ($0 * 8)) & 0xff) } }
        func le16(_ v: Int) -> [UInt8] { [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)] }
        let dataBytes = pcm.count * 2
        var out = Array("RIFF".utf8) + le32(36 + dataBytes) + Array("WAVE".utf8)
        out += Array("fmt ".utf8) + le32(16) + le16(1) + le16(1) + le32(sampleRate) + le32(sampleRate * 2) + le16(2) + le16(16)
        out += Array("data".utf8) + le32(dataBytes)
        var data = Data(out)
        pcm.withUnsafeBufferPointer { data.append(UnsafeRawBufferPointer($0).bindMemory(to: UInt8.self)) }
        return data
    }
}
