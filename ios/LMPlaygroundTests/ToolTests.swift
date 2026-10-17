import Foundation
import SwiftSoup
import Testing
@testable import LMPlayground

/// Ports of the Android tool tests.
struct HtmlToMarkdownTests {

    private func markdown(_ html: String, base: String = "https://example.com/page") throws -> String {
        try HtmlToMarkdown.convert(SwiftSoup.parse(html, base))
    }

    @Test func keepsStructureAndDropsNoise() throws {
        let md = try markdown("""
            <html><body><nav>menu</nav><article>
            <h1>Title</h1><p>Hello <b>bold</b> and <a href="/x">link</a>.</p>
            <ul><li>one</li><li>two</li></ul>
            <pre><code>let a = 1
              indented</code></pre>
            </article><footer>legal</footer></body></html>
            """)
        #expect(md.contains("# Title"))
        #expect(md.contains("Hello **bold** and [link](https://example.com/x)."))
        #expect(md.contains("- one\n- two"))
        #expect(md.contains("```\nlet a = 1\n  indented\n```"))
        #expect(!md.contains("menu"))
        #expect(!md.contains("legal"))
    }

    @Test func blankLinesAreCapped() {
        #expect(HtmlToMarkdown.collapse("a  \n\n\n\nb") == "a\n\nb")
    }
}

struct WebToolTests {

    @Test func duckDuckGoRedirectsAreUnwrapped() {
        #expect(WebSearchTool.extractURL("//duckduckgo.com/l/?uddg=https%3A%2F%2Fswift.org%2Fdocs&rut=x") == "https://swift.org/docs")
        #expect(WebSearchTool.extractURL("//example.com/a") == "https://example.com/a")
        #expect(WebSearchTool.extractURL("https://example.com") == "https://example.com")
    }

    @Test func linkStoreHandsOutAndRestoresReferences() {
        let store = WebLinkStore()
        let a = store.reference("https://a.example")
        let b = store.reference("https://b.example")
        #expect(a == "ddg:1")
        #expect(store.resolve(" \(b) ") == "https://b.example")
        #expect(store.isReference("ddg:42"))

        let restored = WebLinkStore()
        restored.restore(store.snapshot())
        #expect(restored.resolve("ddg:1") == "https://a.example")
        // New references continue past the restored ones.
        #expect(restored.reference("https://c.example") == "ddg:3")
    }

    @Test func linkStoreEvictsTheOldest() {
        let store = WebLinkStore(maxEntries: 2)
        _ = store.reference("1")
        _ = store.reference("2")
        _ = store.reference("3")
        #expect(store.resolve("ddg:1") == nil)
        #expect(store.resolve("ddg:3") == "3")
    }
}
