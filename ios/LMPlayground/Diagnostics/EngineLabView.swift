import SwiftUI
import LlamaKit

/// Debug screen for the M1 device spike: benchmarks every model in
/// Documents/Models. See EngineLab.
struct EngineLabView: View {
    @State private var files: [URL] = []
    @State private var results: [LabResult] = []
    @State private var running = false

    var body: some View {
        NavigationStack {
            List {
                Section("Memory") {
                    LabeledContent("Available", value: "\(ProcessMemory.available.megabytes) MB")
                    LabeledContent("Footprint", value: "\(ProcessMemory.footprint.megabytes) MB")
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
                files = EngineLab.modelFiles()
                if UserDefaults.standard.bool(forKey: "labAutorun"), !running {
                    run()
                }
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

    private func run() {
        running = true
        results = []
        Task {
            _ = await EngineLab.runAll { result in
                Task { @MainActor in results.append(result) }
            }
            running = false
        }
    }
}
