import SwiftUI
import LlamaKit

/// Debug screen for the M1 device spike: benchmarks every model in
/// Documents/Models. See EngineLab.
struct EngineLabView: View {
    @Environment(ModelStore.self) private var store
    @Environment(DownloadManager.self) private var downloads
    @State private var files: [URL] = []
    @State private var status = ""
    @State private var results: [LabResult] = []
    @State private var running = false

    var body: some View {
            List {
                Section("Memory") {
                    LabeledContent("Available", value: "\(ProcessMemory.available.megabytes) MB")
                    LabeledContent("Footprint", value: "\(ProcessMemory.footprint.megabytes) MB")
                }
                if !status.isEmpty {
                    Section("Status") { Text(status) }
                }
                Section("Models in Documents/Models") {
                    if files.isEmpty {
                        Text("Copy GGUF files into Documents/Models.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(files, id: \.self) { url in
                        LabeledContent(url.lastPathComponent, value: "\(EngineLab.fileSize(url).megabytes) MB")
                    }
                }
                Section("Results") {
                    ForEach(results) { r in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(r.file).font(.headline)
                            if let error = r.error {
                                Text(error).foregroundStyle(.red)
                            }
                            Text(summary(r)).font(.caption.monospaced())
                            if let output = r.output {
                                Text(output).font(.caption).foregroundStyle(.secondary).lineLimit(6)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Engine Lab")
            .toolbar {
                Button(running ? "Running…" : "Run") { run() }
                    .disabled(running || files.isEmpty)
            }
            .onAppear {
                // A locked screen suspends the app and stops the GPU mid-run.
                UIApplication.shared.isIdleTimerDisabled = true
                files = EngineLab.modelFiles()
                if let list = UserDefaults.standard.string(forKey: "labDownload"), !running {
                    downloadThenRun(list.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) })
                } else if UserDefaults.standard.bool(forKey: "labAutorun"), !running {
                    run()
                }
            }
    }

    private func summary(_ r: LabResult) -> String {
        var parts = ["\(r.fileMB) MB", "avail \(r.availableBeforeMB) MB"]
        if let s = r.loadSeconds { parts.append(String(format: "load %.1fs", s)) }
        if let m = r.footprintAfterLoadMB { parts.append("fp \(m) MB") }
        if let t = r.ttftSeconds { parts.append(String(format: "ttft %.2fs", t)) }
        if let tps = r.tokensPerSecond { parts.append(String(format: "%.1f/s", tps)) }
        if let p = r.peakFootprintMB { parts.append("peak \(p) MB") }
        if let v = r.visionSeconds { parts.append(String(format: "vision %.1fs", v)) }
        return parts.joined(separator: " · ")
    }

    /// `-labDownload "a.gguf,b.gguf"`: download catalog models (with their
    /// image modules) through the app's DownloadManager, then benchmark.
    private func downloadThenRun(_ filenames: [String]) {
        running = true
        Task {
            for name in filenames {
                guard let model = ModelCatalog.model(forFilename: name) else {
                    status += "unknown model \(name)\n"
                    continue
                }
                downloads.download(model, includeProjector: true)
            }
            while downloads.downloads.values.contains(where: { $0.failure == nil }) {
                let lines = downloads.downloads.map { name, p in
                    "\(name): \(Int(p.fraction * 100))% \(UInt64(p.bytesPerSecond).formattedBytes)/s"
                }
                status = lines.sorted().joined(separator: "\n")
                    + "\n--\n" + (await downloads.taskSummary()).sorted().joined(separator: "\n")
                    + "\nfree: \(store.storageInfo.freeBytes.formattedBytes)"
                try? (Date().formatted() + "\n" + status).write(to: AppDirectories.documents.appending(path: "lab-status.txt"),
                                                               atomically: true, encoding: .utf8)
                try? await Task.sleep(for: .seconds(2))
            }
            let failures = downloads.downloads.compactMap { name, p in p.failure.map { "\(name): \($0)" } }
            status = (failures.isEmpty ? "downloads complete" : failures.joined(separator: "\n"))
                + "\nfree: \(store.storageInfo.freeBytes.formattedBytes)"
            try? (Date().formatted() + "\n" + status).write(to: AppDirectories.documents.appending(path: "lab-status.txt"),
                                                           atomically: true, encoding: .utf8)
            store.refresh()
            files = EngineLab.modelFiles()
            running = false
            run(only: Set(filenames))
        }
    }

    private func run(only: Set<String>? = nil) {
        running = true
        results = []
        Task {
            _ = await EngineLab.runAll(only: only) { result in
                Task { @MainActor in results.append(result) }
            }
            if UserDefaults.standard.bool(forKey: "labCleanAfter") {
                EngineLab.cleanUpModels()
                store.refresh()
                files = EngineLab.modelFiles()
            }
            running = false
        }
    }
}
