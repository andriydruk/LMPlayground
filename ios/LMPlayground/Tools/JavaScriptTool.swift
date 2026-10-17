import Foundation
import WebKit

/// Runs model-written JavaScript in a sandbox (Android: JavaScriptTool, a V8
/// isolate). Here a headless WKWebView: it runs out of process like Android's
/// sandbox, so a runaway loop can't hang the app, and every network load is
/// blocked by a content rule list. Plain JavaScriptCore was rejected because
/// public API can't interrupt it.
struct JavaScriptTool: Tool {
    let name = "run_javascript"
    let description = "Run JavaScript in a sandboxed engine; returns the last expression's value. Use for math, date and time (`new Date()`), strings, JSON, and regex. No network, filesystem, or DOM."
    let parametersSchema = #"{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source; the last expression's value is returned. E.g. '(2 + 3) * 4', 'new Date().toString()'."}},"required":["code"]}"#

    static let timeout: Duration = .seconds(10)

    func cancelInFlight() async {
        await Sandbox.shared.cancel()
    }

    func execute(arguments: String) async -> String {
        guard let args = try? JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Any],
              let code = args["code"] as? String else {
            return errorJSON("Missing code")
        }
        do {
            let result = try await Sandbox.shared.evaluate(code)
            let data = try JSONSerialization.data(withJSONObject: ["result": result])
            return String(decoding: data, as: UTF8.self)
        } catch {
            return errorJSON(error.localizedDescription)
        }
    }

    @MainActor
    private final class Flag {
        var done = false
    }

    /// One short-lived web view per evaluation, torn down afterwards.
    @MainActor
    final class Sandbox {
        static let shared = Sandbox()

        private var webView: WKWebView?
        private var blockAll: WKContentRuleList?

        func cancel() {
            webView?.stopLoading()
            webView = nil
        }

        func evaluate(_ code: String) async throws -> String {
            let configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = .nonPersistent()
            if blockAll == nil {
                blockAll = try await WKContentRuleListStore.default().compileContentRuleList(
                    forIdentifier: "lmp-block-all",
                    encodedContentRuleList: #"[{"trigger":{"url-filter":".*"},"action":{"type":"block"}}]"#)
            }
            if let blockAll { configuration.userContentController.add(blockAll) }
            let view = WKWebView(frame: .zero, configuration: configuration)
            webView = view
            defer { if webView === view { webView = nil } }

            // eval() of a JSON-quoted string (not a template literal, which
            // would interpolate ${...} and break on backticks), as on Android.
            let finished = Flag()
            return try await withCheckedThrowingContinuation { continuation in
                let timer = Task { @MainActor in
                    try? await Task.sleep(for: JavaScriptTool.timeout)
                    guard !finished.done else { return }
                    finished.done = true
                    view.stopLoading()
                    continuation.resume(throwing: NSError(domain: "JavaScriptTool", code: 1, userInfo: [
                        NSLocalizedDescriptionKey: "Timed out after 10 seconds"]))
                }
                view.callAsyncJavaScript("return String(eval(code));", arguments: ["code": code],
                                         in: nil, in: .defaultClient) { result in
                    guard !finished.done else { return }
                    finished.done = true
                    timer.cancel()
                    switch result {
                    case .success(let value): continuation.resume(returning: value as? String ?? "undefined")
                    case .failure(let error): continuation.resume(throwing: error)
                    }
                }
            }
        }
    }
}
