import AVFoundation
import LlamaKit
import Observation

/// Drives voice dictation for the text field (Android: DictationController).
///
/// Audio goes to the recognizer as it's recorded and text comes back within a
/// few hundred milliseconds, so words appear while the user is speaking.
///
/// Recording and recognition are separate. The recognizer can run behind the
/// microphone, so it may still be draining its backlog after the user taps
/// off. Tapping on again then isn't swallowed: the new recording starts at
/// once, its audio waits for the recognizer, and both utterances land in the
/// order they were spoken.
///
/// The mic button is a toggle: one tap starts, the next stops. `micOn` is what
/// the button shows; `isRecording` and `level` drive its ring.
@Observable
@MainActor
final class DictationController {

    enum State: Equatable {
        case idle
        /// Recording, recognizing, or both. `text` is everything recognized
        /// in this session so far.
        case listening(String)
        /// Recording has ended; the recognizer is still catching up.
        case finishing
    }

    private(set) var state = State.idle
    /// Set the moment the user taps on, before the model check and load.
    private(set) var micOn = false
    /// From the first audio the microphone delivers until it stops: the
    /// "you're being recorded" cue, unlike `micOn`.
    private(set) var isRecording = false
    /// Loudness of the audio being recorded, 0 (silence) to 1 (loud speech).
    private(set) var level: Float = 0
    /// The finished text, waiting for the text field to insert it.
    private(set) var transcript: String?
    var errorMessage: String?
    /// The mic was tapped but the model isn't downloaded.
    var needsModelDownload = false

    let model = ModelCatalog.dictationModel

    private let engine: DictationEngine
    private let downloads: DownloadManager?
    private let makeSource: () -> any AudioSource

    /// One dictation session: from the first tap until everything spoken is
    /// recognized and handed over. A tap while the recognizer is catching up
    /// joins the running session.
    private final class Session {
        var transcript = DictationTranscript()
        /// Recordings plus recognitions in flight; at zero the session is done.
        var outstanding = 0
        /// Set by `abandon()`: nothing from this session reaches the text field.
        var abandoned = false
        var tasks: [Task<Void, Never>] = []
    }

    private var current = Session()
    /// The microphone is busy; one recording at a time.
    private var recording = false
    /// Whether the user wants the mic on. Starting is asynchronous, so they
    /// can tap off before recording begins.
    private var held = false
    /// Bumped on every tap on. A recording remembers the one it served.
    private var micRequest = 0
    /// A tap on arrived while the previous recording was still releasing the
    /// microphone; that recording starts this one when it lets go.
    private var startPending = false
    private var source: (any AudioSource)?
    /// Recognition of earlier utterances; a new one waits for it.
    private var recognitionTail: Task<Void, Never>?

    init(engine: DictationEngine = .shared, downloads: DownloadManager?,
         makeSource: @escaping () -> any AudioSource = { MicrophoneCapture() }) {
        self.engine = engine
        self.downloads = downloads
        self.makeSource = makeSource
    }

    /// Download progress of the dictation model (0...1), or nil when none is
    /// running. The model is ~700 MB, so the mic shows the wait.
    var downloadProgress: Double? {
        guard let progress = downloads?.downloads[model.filename], progress.failure == nil else { return nil }
        return progress.fraction
    }

    // MARK: Taps

    /// The mic button was tapped.
    func micTapped() async {
        if micOn {
            onMicReleased()
            return
        }
        // Already fetching: the ring around the mic is the answer.
        if downloadProgress != nil { return }
        onMicPressed()
        guard engine.isModelOnDisk() else {
            onMicReleased()
            needsModelDownload = true
            return
        }
        guard await AVAudioApplication.requestRecordPermission() else {
            onMicReleased()
            errorMessage = String(localized: "Allow microphone access to dictate.")
            return
        }
        startListening()
    }

    func downloadModel() {
        guard let url = model.remoteURL.flatMap(URL.init(string:)) else { return }
        downloads?.start(filename: model.filename, url: url)
    }

    /// The user turned the mic on, before any async work.
    func onMicPressed() {
        held = true
        micRequest += 1
        micOn = true
    }

    /// The user turned the mic off (or it never started).
    func onMicReleased() {
        held = false
        micOn = false
        stopListening()
    }

    // MARK: Recording

    /// Start recording. The caller has the microphone permission.
    func startListening() {
        // Turned off again before the start caught up.
        guard held else { return }
        // Only the microphone is exclusive; recognition of the previous
        // utterance may still run. A recording still letting go of the mic
        // starts this one when it has.
        if recording {
            startPending = true
            return
        }
        let source = makeSource()
        let audio: AsyncThrowingStream<AudioChunk, Error>
        do {
            audio = try source.start()
        } catch {
            held = false
            micOn = false
            errorMessage = String(localized: "Couldn't start recording.")
            return
        }
        recording = true
        self.source = source
        let request = micRequest
        let session = current
        session.outstanding += 1
        state = .listening(session.transcript.text)

        // Audio is buffered so recording never waits for the recognizer.
        let (forward, forwarding) = AsyncStream<[Float]>.makeStream()
        let capture = Task { [weak self] in
            do {
                for try await chunk in audio {
                    guard let self else { break }
                    if !self.isRecording { self.isRecording = true }
                    self.level = Self.level(rms: chunk.amplitude)
                    forwarding.yield(chunk.samples)
                }
            } catch {
                self?.errorMessage = String(localized: "Couldn't start recording.")
            }
            forwarding.finish()
            self?.recordingEnded(request: request)
        }
        let previous = recognitionTail
        let recognition = Task { [weak self] in
            await previous?.value
            await self?.recognize(forward, session: session)
            await capture.value
            self?.utteranceFinished(session)
        }
        recognitionTail = recognition
        session.tasks += [capture, recognition]
    }

    /// Finish the current recording and keep what was recognized.
    func stopListening() {
        held = false
        source?.stop()
        if current.outstanding > 0 { state = .finishing }
    }

    /// The user took the text over (edited it, or sent it) while dictation
    /// was still writing into it: stop at once and deliver nothing more.
    func abandon() {
        held = false
        startPending = false
        micOn = false
        let session = current
        current = Session()
        session.abandoned = true
        source?.stop()
        session.tasks.forEach { $0.cancel() }
        state = .idle
    }

    func consumeTranscript() {
        transcript = nil
    }

    // MARK: Recognition

    private func recognize(_ audio: AsyncStream<[Float]>, session: Session) async {
        guard !session.abandoned else { return }
        let engine = self.engine
        let stream: AsrStream
        do {
            stream = try await engine.beginStream()
        } catch {
            if !session.abandoned { errorMessage = String(localized: "Couldn't transcribe the recording.") }
            return
        }
        for await samples in audio {
            let delta = await engine.feed(stream, samples)
            if !delta.isEmpty { append(delta, to: session) }
        }
        if Task.isCancelled || session.abandoned {
            await engine.close(stream)
        } else {
            let tail = await engine.finish(stream)
            if !tail.isEmpty { append(tail, to: session) }
        }
        // The next stream detokenizes afresh, so its first word must not run
        // into this one's last.
        session.transcript.endUtterance()
    }

    /// The delta goes in verbatim: it carries its own spacing and often ends
    /// mid-word.
    private func append(_ delta: String, to session: Session) {
        guard !session.abandoned else { return }
        session.transcript.append(delta)
        state = .listening(session.transcript.text)
    }

    /// A recording let go of the microphone. If it still served the latest
    /// tap, the button turns off (that covers the duration cap and errors).
    /// If the user already tapped on again, that start runs now.
    private func recordingEnded(request: Int) {
        recording = false
        isRecording = false
        level = 0
        source = nil
        if request == micRequest {
            held = false
            micOn = false
        } else if startPending {
            startPending = false
            startListening()
        }
        // Let the stop tone play, then give the audio session back.
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard let self, !self.recording else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func utteranceFinished(_ session: Session) {
        session.outstanding -= 1
        // Another tap joined (its own completion finishes the session), or
        // the session was abandoned.
        guard session.outstanding == 0, current === session, !session.abandoned else { return }
        current = Session()
        // Nothing recognized needs no message: the stop tone and the ring
        // going away say it ended.
        transcript = session.transcript.text
        state = .idle
    }

    // MARK: Level

    /// Quiet-room noise and loud close speech, in dBFS.
    nonisolated private static let levelFloor: Float = -55
    nonisolated private static let levelCeiling: Float = -15

    /// RMS amplitude to 0...1 on a decibel scale, which is how loudness is
    /// heard: linear RMS barely moves for normal speech.
    nonisolated static func level(rms: Float) -> Float {
        guard rms > 0 else { return 0 }
        let db = 20 * log10(rms)
        return min(1, max(0, (db - levelFloor) / (levelCeiling - levelFloor)))
    }
}
