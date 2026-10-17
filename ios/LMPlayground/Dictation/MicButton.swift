import SwiftUI

/// The dictation toggle (Android: MicButton): a plain microphone when off; on
/// a soft disc once tapped (model check and load); and with a slowly turning
/// colour ring that breathes with the voice once audio is actually being
/// recorded, with a haptic tick and a rising tone. The cue waits for the
/// first audio, not the tap: speech during the model load would be lost.
struct MicButton: View {
    @Environment(DictationController.self) private var dictation
    var enabled: Bool

    private var listening: Bool { dictation.micOn && dictation.isRecording }

    var body: some View {
        Button {
            Task { await dictation.micTapped() }
        } label: {
            ZStack {
                Circle()
                    .fill(.primary.opacity(dictation.micOn ? 0.12 : 0))
                    .frame(width: 36, height: 36)
                if listening {
                    ListeningRing(level: dictation.level)
                        .transition(.opacity)
                }
                if let progress = dictation.downloadProgress {
                    ProgressView(value: progress)
                        .progressViewStyle(.circular)
                        .controlSize(.regular)
                        .frame(width: 40, height: 40)
                }
                Image(systemName: "mic.fill")
                    .font(.title3)
            }
            .frame(width: 40, height: 40)
            .animation(.easeInOut(duration: 0.2), value: dictation.micOn)
            .animation(.spring(duration: 0.3), value: listening)
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .accessibilityLabel(dictation.micOn ? "Stop dictation" : "Start dictation")
        .accessibilityIdentifier("micButton")
        .sensoryFeedback(.start, trigger: listening) { _, now in now }
        .onChange(of: listening) { was, now in
            if now {
                DictationEarcon.playStart()
            } else if was {
                DictationEarcon.playStop()
            }
        }
    }
}

/// The turning ring around a recording mic; thicker and wider as the speaker
/// gets louder.
private struct ListeningRing: View {
    var level: Float

    private static let colors: [Color] = [
        Color(red: 0.31, green: 0.55, blue: 1.0),
        Color(red: 0.70, green: 0.42, blue: 1.0),
        Color(red: 1.0, green: 0.42, blue: 0.60),
        Color(red: 1.0, green: 0.71, blue: 0.28),
        Color(red: 0.24, green: 0.86, blue: 0.59),
        Color(red: 0.31, green: 0.55, blue: 1.0),
    ]

    var body: some View {
        let voice = CGFloat(level)
        TimelineView(.animation) { context in
            let angle = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3.2) / 3.2 * 360
            Circle()
                .strokeBorder(AngularGradient(colors: Self.colors, center: .center),
                              lineWidth: 2 + 2.5 * voice)
                .rotationEffect(.degrees(angle))
                .frame(width: 38 + 6 * voice, height: 38 + 6 * voice)
        }
        .animation(.spring(duration: 0.15), value: level)
        .allowsHitTesting(false)
    }
}
