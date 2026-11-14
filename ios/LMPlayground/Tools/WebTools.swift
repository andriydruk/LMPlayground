import Foundation
import SwiftSoup

/// One URLSession per tool so Stop can cancel just its requests.
private func makeSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 8
    config.timeoutIntervalForResource = 20
    config.httpAdditionalHeaders = ["User-Agent": webUserAgent]
    return URLSession(configuration: config)
}

private let webUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

private func json(_ object: Any) -> String {
    let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
    return String(decoding: data, as: UTF8.self)
}

/// DuckDuckGo HTML search (Android: WebSearchTool).
struct WebSearchTool: Tool {
    let name = "web_search"
    let description = "Search the web and return results with titles, snippets, and a compact reference for each result. Pass a result's \"ref\" to web_fetch to read that page."
    let parametersSchema = #"{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"max_results":{"type":"integer","description":"Maximum number of results to return (default 5, max 10)"}},"required":["query"]}"#

    let links: WebLinkStore
    private let session = makeSession()

    init(links: WebLinkStore) {
        self.links = links
    }

    func cancelInFlight() async {
        for task in await session.allTasks { task.cancel() }
    }

    func execute(arguments: String) async -> String {
        guard let args = try? JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Any],
              let query = args["query"] as? String else {
            return errorJSON("Missing query")
        }
        let maxResults = min(max((args["max_results"] as? Int) ?? 5, 1), 10)
        var components = URLComponents(string: "https://html.duckduckgo.com/html/")!
        components.queryItems = [URLQueryItem(name: "q", value: query)]
        do {
            let (data, _) = try await session.data(from: components.url!)
            let document = try SwiftSoup.parse(String(decoding: data, as: UTF8.self))
            var results: [[String: String]] = []
            for element in try document.select(".result.results_links") {
                if results.count >= maxResults { break }
                guard let titleElement = try element.select(".result__title a").first() else { continue }
                let title = try titleElement.text().trimmingCharacters(in: .whitespaces)
                let url = Self.extractURL(try titleElement.attr("href"))
                let snippet = try element.select(".result__snippet").first()?.text().trimmingCharacters(in: .whitespaces) ?? ""
                if title.isEmpty || url.isEmpty { continue }
                // A compact reference instead of the URL saves context
                // tokens; web_fetch resolves it back.
                results.append(["title": title, "ref": links.reference(url), "snippet": snippet])
            }
            if results.isEmpty {
                return json(["results": [], "query": query, "message": "No results found"])
            }
            return json(["results": results, "query": query])
        } catch {
            return errorJSON(error.localizedDescription)
        }
    }

    /// DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<encoded>&...
    static func extractURL(_ href: String) -> String {
        if let range = href.range(of: "uddg=") {
            let encoded = href[range.upperBound...].prefix { $0 != "&" }
            return String(encoded).removingPercentEncoding ?? String(encoded)
        }
        if href.hasPrefix("http") { return href }
        if href.hasPrefix("//") { return "https:" + href }
        return href
    }
}

/// Fetch a page as Markdown (Android: WebFetchTool).
struct WebFetchTool: Tool {
    let name = "web_fetch"
    let description = "Fetch a web page and return its main content as markdown. Headings, lists, links and code blocks are preserved so you can read the page structure."
    let parametersSchema = #"{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch, or a reference from web_search results such as \"ddg:3\""},"max_length":{"type":"integer","description":"Maximum content length in characters (default 5000)"}},"required":["url"]}"#

    let links: WebLinkStore
    private let session = makeSession()

    init(links: WebLinkStore) {
        self.links = links
    }

    func cancelInFlight() async {
        for task in await session.allTasks { task.cancel() }
    }

    func execute(arguments: String) async -> String {
        guard let args = try? JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Any],
              let raw = (args["url"] as? String)?.trimmingCharacters(in: .whitespaces) else {
            return errorJSON("Missing url")
        }
        let resolved = links.resolve(raw)
        if resolved == nil, links.isReference(raw) {
            return errorJSON("Unknown search reference '\(raw)'. Call web_search first, then fetch a returned ref.")
        }
        let target = resolved ?? raw
        let urlString = target.hasPrefix("http") ? target : "https://" + target
        guard let url = URL(string: urlString) else { return errorJSON("Invalid URL") }
        let maxLength = min(max((args["max_length"] as? Int) ?? 5000, 50), 20000)

        do {
            let (data, response) = try await session.data(from: url)
            let contentType = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? ""
            let body = String(decoding: data, as: UTF8.self)
            var title = ""
            var content: String
            if contentType.contains("text/html") || contentType.contains("application/xhtml") {
                let document = try SwiftSoup.parse(body, urlString)
                title = try document.title()
                content = try HtmlToMarkdown.convert(document)
            } else {
                content = body
            }
            if content.count > maxLength { content = String(content.prefix(maxLength)) + "..." }
            return json(["url": urlString, "title": title, "content": content, "length": content.count])
        } catch {
            return errorJSON(error.localizedDescription)
        }
    }
}

/// HTML → Markdown tuned for LLM consumption. Port of HtmlToMarkdown.kt: keeps
/// headings, paragraphs, lists, links and code; drops navigation and noise.
enum HtmlToMarkdown {

    static func convert(_ document: Document) throws -> String {
        let root: Element = try document.select("article").first()
            ?? document.select("main").first()
            ?? document.body()
            ?? document
        try root.select("script, style, nav, footer, header, aside, form, iframe, noscript, .ad, .ads, .advertisement, [aria-hidden=true]").remove()
        var out = ""
        try renderChildren(root, &out)
        return collapse(out)
    }

    private static func renderNode(_ node: Node, _ out: inout String) throws {
        if let text = node as? TextNode {
            out += text.getWholeText().replacing(/\s+/, with: " ")
        } else if let element = node as? Element {
            try renderElement(element, &out)
        }
    }

    private static func renderChildren(_ element: Element, _ out: inout String) throws {
        for child in element.getChildNodes() { try renderNode(child, &out) }
    }

    private static func renderElement(_ el: Element, _ out: inout String) throws {
        switch el.tagName().lowercased() {
        case "h1", "h2", "h3", "h4", "h5", "h6":
            let level = Int(el.tagName().dropFirst()) ?? 1
            out += "\n\n" + String(repeating: "#", count: level) + " "
            try renderChildren(el, &out)
            out += "\n\n"
        case "p":
            out += "\n\n"
            try renderChildren(el, &out)
            out += "\n\n"
        case "br":
            out += "\n"
        case "hr":
            out += "\n\n---\n\n"
        case "strong", "b":
            try wrap(el, "**", &out)
        case "em", "i":
            try wrap(el, "*", &out)
        case "del", "s", "strike":
            try wrap(el, "~~", &out)
        case "code":
            if el.parent()?.tagName().lowercased() == "pre" { try renderChildren(el, &out) } else { try wrap(el, "`", &out) }
        case "pre":
            out += "\n\n```\n" + wholeText(el)
            if !out.hasSuffix("\n") { out += "\n" }
            out += "```\n\n"
        case "blockquote":
            var inner = ""
            try renderChildren(el, &inner)
            for line in inner.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "\n", omittingEmptySubsequences: false) {
                out += "> " + line + "\n"
            }
            out += "\n"
        case "ul", "ol":
            let ordered = el.tagName().lowercased() == "ol"
            out += "\n"
            var index = 1
            for child in el.children() where child.tagName().lowercased() == "li" {
                out += ordered ? "\(index). " : "- "
                index += 1
                var item = ""
                try renderChildren(child, &item)
                out += item.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: " ") + "\n"
            }
            out += "\n"
        case "li":
            out += "- "
            try renderChildren(el, &out)
            out += "\n"
        case "a":
            var href = try el.absUrl("href")
            if href.isEmpty { href = try el.attr("href") }
            href = href.trimmingCharacters(in: .whitespaces)
            if href.isEmpty || href.hasPrefix("#") || href.lowercased().hasPrefix("javascript:") {
                try renderChildren(el, &out)
            } else {
                out += "["
                try renderChildren(el, &out)
                out += "](\(href))"
            }
        case "img":
            let alt = try el.attr("alt").trimmingCharacters(in: .whitespaces)
            if !alt.isEmpty { out += alt }
        case "table":
            out += "\n"
            for row in try el.select("tr") {
                let cells = try row.select("th, td").map { try $0.text().trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                if !cells.isEmpty { out += cells.joined(separator: " | ") + "\n" }
            }
            out += "\n"
        case "div", "section", "article", "main", "body", "html", "figure", "figcaption":
            try renderChildren(el, &out)
            if !out.hasSuffix("\n\n") { out += "\n" }
        default:
            try renderChildren(el, &out)
        }
    }

    /// Text with whitespace kept verbatim, for <pre>.
    private static func wholeText(_ el: Element) -> String {
        var text = ""
        for child in el.getChildNodes() {
            if let node = child as? TextNode {
                text += node.getWholeText()
            } else if let element = child as? Element {
                text += element.tagName().lowercased() == "br" ? "\n" : wholeText(element)
            }
        }
        return text
    }

    private static func wrap(_ el: Element, _ marker: String, _ out: inout String) throws {
        out += marker
        try renderChildren(el, &out)
        out += marker
    }

    /// Trailing spaces off each line; blank-line runs capped at one.
    static func collapse(_ s: String) -> String {
        s.replacing(/[ \t]+\n/, with: "\n")
            .replacing(/\n{3,}/, with: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
