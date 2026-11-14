import Foundation
import Observation
import UserNotifications

/// Model downloads on a background URLSession: nsurlsessiond keeps
/// transferring while the app is suspended or terminated, and interrupted
/// transfers resume from where they stopped.
@Observable
@MainActor
final class DownloadManager {

    struct Progress: Sendable {
        var received: Int64 = 0
        var expected: Int64 = 0
        var bytesPerSecond: Double = 0
        var failure: String?

        var fraction: Double { expected > 0 ? Double(received) / Double(expected) : 0 }
        var remainingSeconds: Double? {
            guard bytesPerSecond > 0, expected > received else { return nil }
            return Double(expected - received) / bytesPerSecond
        }
    }

    /// In-flight and failed downloads, keyed by destination filename.
    private(set) var downloads: [String: Progress] = [:]

    /// Multi-GB files: off by default on cellular.
    var allowsCellular: Bool {
        get { access(keyPath: \.allowsCellular); return UserDefaults.standard.bool(forKey: Self.cellularKey) }
        set { withMutation(keyPath: \.allowsCellular) { UserDefaults.standard.set(newValue, forKey: Self.cellularKey) } }
    }

    /// Called with each filename that finished.
    var onFinished: ((String) -> Void)?

    /// Set by the app delegate when iOS relaunches the app for session events.
    var backgroundCompletionHandler: (() -> Void)?

    private static let cellularKey = "downloadOverCellular"
    private static let sessionIdentifier = "com.druk.lmplayground.downloads"
    private static let maxRetries = 5

    private let directory: URL
    @ObservationIgnored private var session: URLSession!
    @ObservationIgnored private var delegate: Delegate!
    @ObservationIgnored private var sources: [String: URL] = [:]
    @ObservationIgnored private var retries: [String: Int] = [:]
    @ObservationIgnored private var lastSample: [String: (time: Date, bytes: Int64)] = [:]

    init(directory: URL = AppDirectories.models) {
        self.directory = directory
        delegate = Delegate(directory: directory, manager: self)
        #if targetEnvironment(simulator)
        // The simulator has no nsurlsessiond: background sessions fail with -1.
        let configuration = URLSessionConfiguration.default
        #else
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        #endif
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        restore()
    }

    func isDownloading(_ filename: String) -> Bool {
        if let p = downloads[filename] { return p.failure == nil }
        return false
    }

    /// Download a catalog model, and its vision module when asked.
    func download(_ model: CatalogModel, includeProjector: Bool) {
        if let remote = model.remoteURL.flatMap(URL.init(string:)) {
            start(filename: model.filename, url: remote)
        }
        if includeProjector, let name = model.mmprojFilename, let remote = model.mmprojURL.flatMap(URL.init(string:)) {
            start(filename: name, url: remote)
        }
    }

    func start(filename: String, url: URL) {
        guard !FileManager.default.fileExists(atPath: directory.appending(path: filename).path) else { return }
        requestNotificationPermission()
        sources[filename] = url
        retries[filename] = 0
        if downloads[filename] == nil || downloads[filename]?.failure != nil {
            downloads[filename] = Progress()
        }
        // Reuse a live transfer for this file (one that survived a relaunch)
        // rather than starting a second one; otherwise start fresh.
        Task {
            let live = await session.allTasks.filter {
                $0.taskDescription == filename && ($0.state == .running || $0.state == .suspended)
            }
            if let task = live.first {
                if task.state == .suspended { task.resume() }
                return
            }
            session.downloadTask(with: request(for: url)).tagged(filename).resume()
        }
    }

    func cancel(_ filename: String) {
        session.getAllTasks { tasks in
            tasks.filter { $0.taskDescription == filename }.forEach { $0.cancel() }
        }
        downloads[filename] = nil
        sources[filename] = nil
    }

    /// One line per URLSession task, for diagnostics.
    func taskSummary() async -> [String] {
        let tasks = await session.allTasks
        let states = ["running", "suspended", "canceling", "completed"]
        return tasks.map { task in
            let state = states.indices.contains(task.state.rawValue) ? states[task.state.rawValue] : "?"
            let error = task.error.map { " error=\($0.localizedDescription)" } ?? ""
            let name = task.taskDescription ?? "?"
            return "\(name) task=\(state) \(UInt64(task.countOfBytesReceived).formattedBytes)/\(UInt64(max(task.countOfBytesExpectedToReceive, 0)).formattedBytes) retries=\(retries[name] ?? 0)\(error)"
        }
    }

    func dismissFailure(_ filename: String) {
        if downloads[filename]?.failure != nil { downloads[filename] = nil }
    }

    private func request(for url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.allowsExpensiveNetworkAccess = allowsCellular
        request.allowsCellularAccess = allowsCellular
        return request
    }

    /// Re-attach progress to transfers that survived a relaunch. Tasks the
    /// system already cancelled or finished are left out: their outcome
    /// arrives through the delegate.
    private func restore() {
        session.getAllTasks { [weak self] tasks in
            let running = tasks.compactMap { task -> (String, Int64, Int64)? in
                guard let name = task.taskDescription,
                      task.state == .running || task.state == .suspended else { return nil }
                if task.state == .suspended { task.resume() }
                return (name, task.countOfBytesReceived, task.countOfBytesExpectedToReceive)
            }
            Task { @MainActor in
                for (name, received, expected) in running {
                    self?.downloads[name] = Progress(received: received, expected: expected)
                }
            }
        }
    }

    // MARK: - Delegate events (main actor)

    fileprivate func didWrite(_ filename: String, received: Int64, expected: Int64, task: URLSessionTask) {
        var progress = downloads[filename] ?? Progress()
        let now = Date()
        if let last = lastSample[filename] {
            let elapsed = now.timeIntervalSince(last.time)
            if elapsed >= 0.5 {
                let rate = Double(received - last.bytes) / elapsed
                progress.bytesPerSecond = progress.bytesPerSecond == 0 ? rate : 0.7 * progress.bytesPerSecond + 0.3 * rate
                lastSample[filename] = (now, received)
            }
        } else {
            lastSample[filename] = (now, received)
            // First bytes: the size is known now, so check it fits.
            let free = (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage) ?? Int64.max
            if expected > 0, expected > free {
                task.cancel()
                progress.failure = String(localized: "Not enough free space (\(UInt64(expected).formattedBytes) needed).")
                downloads[filename] = progress
                return
            }
        }
        progress.received = received
        progress.expected = expected
        downloads[filename] = progress
    }

    fileprivate func didFinish(_ filename: String) {
        downloads[filename] = nil
        sources[filename] = nil
        lastSample[filename] = nil
        onFinished?(filename)
        notifyFinished(filename)
    }

    fileprivate func didFail(_ filename: String, error: Error, resumeData: Data?) {
        if (error as? URLError)?.code == .cancelled, resumeData == nil {
            return  // cancelled by the user
        }
        let attempt = (retries[filename] ?? 0) + 1
        retries[filename] = attempt
        if attempt <= Self.maxRetries {
            // Back off 2, 4, 8... seconds, then resume where the transfer stopped.
            let delay = Duration.seconds(1 << attempt)
            Task {
                try? await Task.sleep(for: delay)
                if let resumeData {
                    self.session.downloadTask(withResumeData: resumeData).tagged(filename).resume()
                } else if let url = self.sources[filename] {
                    self.session.downloadTask(with: self.request(for: url)).tagged(filename).resume()
                }
            }
            return
        }
        var progress = downloads[filename] ?? Progress()
        progress.failure = error.localizedDescription
        downloads[filename] = progress
    }

    fileprivate func didFinishBackgroundEvents() {
        backgroundCompletionHandler?()
        backgroundCompletionHandler = nil
    }

    // MARK: - Notifications

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notifyFinished(_ filename: String) {
        guard !MmprojPairing.isMmproj(filename) else { return }
        let name = ModelCatalog.model(forFilename: filename)?.name ?? filename
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Download complete")
        content.body = String(localized: "\(name) is ready to use.")
        let request = UNNotificationRequest(identifier: "download-\(filename)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - URLSession delegate

    /// Runs on the session's queue. The finished file must be moved before
    /// `didFinishDownloadingTo` returns, so that happens here.
    private final class Delegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
        let directory: URL
        weak var manager: DownloadManager?

        init(directory: URL, manager: DownloadManager) {
            self.directory = directory
            self.manager = manager
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                        totalBytesExpectedToWrite: Int64) {
            guard let name = downloadTask.taskDescription else { return }
            Task { @MainActor [weak manager] in
                manager?.didWrite(name, received: totalBytesWritten, expected: totalBytesExpectedToWrite, task: downloadTask)
            }
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didFinishDownloadingTo location: URL) {
            guard let name = downloadTask.taskDescription else { return }
            if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                return  // reported by didCompleteWithError below as a server error
            }
            let destination = directory.appending(path: name)
            do {
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: location, to: destination)
                try ModelFiles.excludeFromBackup(destination)
                Task { @MainActor [weak manager] in manager?.didFinish(name) }
            } catch {
                Task { @MainActor [weak manager] in manager?.didFail(name, error: error, resumeData: nil) }
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            guard let name = task.taskDescription else { return }
            var failure = error
            if failure == nil, let http = task.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                failure = URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey:
                    String(localized: "The server answered \(http.statusCode).")])
            }
            guard let failure else { return }
            let resumeData = (failure as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data
            Task { @MainActor [weak manager] in manager?.didFail(name, error: failure, resumeData: resumeData) }
        }

        func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
            Task { @MainActor [weak manager] in manager?.didFinishBackgroundEvents() }
        }
    }
}

private extension URLSessionDownloadTask {
    func tagged(_ filename: String) -> URLSessionDownloadTask {
        taskDescription = filename
        return self
    }
}
