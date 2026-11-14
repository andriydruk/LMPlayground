import Markdown
import SwiftUI

/// Renders a model's Markdown answer (GFM): headings, paragraphs, emphasis,
/// strikethrough, inline and fenced code, links, lists, quotes, rules and
/// tables. Android: MessageFormatter.
struct MarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(Self.blocks(text).enumerated()), id: \.offset) { _, block in
                BlockView(block: block)
            }
        }
        .textSelection(.enabled)
    }

    private final class Parsed {
        let blocks: [any BlockMarkup]
        init(_ blocks: [any BlockMarkup]) { self.blocks = blocks }
    }

    /// Parsed once per text: replies scrolled back into view are laid out
    /// again without parsing.
    @MainActor private static let cache: NSCache<NSString, Parsed> = {
        let cache = NSCache<NSString, Parsed>()
        cache.countLimit = 200
        return cache
    }()

    private static func blocks(_ text: String) -> [any BlockMarkup] {
        if let parsed = cache.object(forKey: text as NSString) { return parsed.blocks }
        let blocks = Array(Document(parsing: text).blockChildren)
        cache.setObject(Parsed(blocks), forKey: text as NSString)
        return blocks
    }
}

private struct BlockView: View {
    let block: any BlockMarkup

    var body: some View {
        switch block {
        case let heading as Heading:
            SwiftUI.Text(Inline.render(heading))
                .font(heading.level <= 1 ? .title2.bold() : heading.level == 2 ? .title3.bold() : .headline)
        case let paragraph as Paragraph:
            SwiftUI.Text(Inline.render(paragraph))
        case let code as CodeBlock:
            CodeBlockView(code: code.code, language: code.language)
        case let list as UnorderedList:
            ListView(items: Array(list.listItems), ordered: false, start: 1)
        case let list as OrderedList:
            ListView(items: Array(list.listItems), ordered: true, start: Int(list.startIndex))
        case let quote as BlockQuote:
            HStack(alignment: .top, spacing: 8) {
                RoundedRectangle(cornerRadius: 1.5).fill(.tertiary).frame(width: 3)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(quote.blockChildren.enumerated()), id: \.offset) { _, child in
                        BlockView(block: child)
                    }
                }
                .foregroundStyle(.secondary)
            }
        case is ThematicBreak:
            Divider()
        case let table as Markdown.Table:
            TableView(table: table)
        case let html as HTMLBlock:
            SwiftUI.Text(html.rawHTML).font(.callout.monospaced())
        default:
            SwiftUI.Text(block.format())
        }
    }
}

private struct ListView: View {
    let items: [ListItem]
    let ordered: Bool
    let start: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    SwiftUI.Text(marker(index, item))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(item.blockChildren.enumerated()), id: \.offset) { _, child in
                            BlockView(block: child)
                        }
                    }
                }
            }
        }
    }

    private func marker(_ index: Int, _ item: ListItem) -> String {
        switch item.checkbox {
        case .checked: return "☑"
        case .unchecked: return "☐"
        case nil: return ordered ? "\(start + index)." : "•"
        }
    }
}

private struct CodeBlockView: View {
    let code: String
    let language: String?
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                SwiftUI.Text(language?.isEmpty == false ? language! : "code")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                Spacer()
                Button(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    UIPasteboard.general.string = code
                    copied = true
                    Task { try? await Task.sleep(for: .seconds(2)); copied = false }
                }
                .font(.caption)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                SwiftUI.Text(code.hasSuffix("\n") ? String(code.dropLast()) : code)
                    .font(.callout.monospaced())
                    .padding(12)
            }
        }
        .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct TableView: View {
    let table: Markdown.Table

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow {
                    ForEach(Array(table.head.cells.enumerated()), id: \.offset) { _, cell in
                        SwiftUI.Text(Inline.render(cell)).bold()
                    }
                }
                Divider()
                ForEach(Array(table.body.rows.enumerated()), id: \.offset) { _, row in
                    GridRow {
                        ForEach(Array(row.cells.enumerated()), id: \.offset) { _, cell in
                            SwiftUI.Text(Inline.render(cell))
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Inline Markdown → AttributedString.
private enum Inline {
    static func render(_ markup: any Markup) -> AttributedString {
        var result = AttributedString()
        for child in markup.children {
            result += node(child)
        }
        return result
    }

    private static func node(_ markup: any Markup) -> AttributedString {
        switch markup {
        case let text as Markdown.Text:
            return AttributedString(text.string)
        case let strong as Strong:
            var s = render(strong)
            s.inlinePresentationIntent = (s.inlinePresentationIntent ?? []).union(.stronglyEmphasized)
            return s
        case let emphasis as Emphasis:
            var s = render(emphasis)
            s.inlinePresentationIntent = (s.inlinePresentationIntent ?? []).union(.emphasized)
            return s
        case let strike as Strikethrough:
            var s = render(strike)
            s.strikethroughStyle = .single
            return s
        case let code as InlineCode:
            var s = AttributedString(code.code)
            s.font = .body.monospaced()
            s.backgroundColor = Color.secondary.opacity(0.15)
            return s
        case let link as Markdown.Link:
            var s = render(link)
            if let destination = link.destination, let url = URL(string: destination) {
                s.link = url
            }
            return s
        case is SoftBreak:
            return AttributedString(" ")
        case is LineBreak:
            return AttributedString("\n")
        case let html as InlineHTML:
            return AttributedString(html.rawHTML)
        case let image as Markdown.Image:
            return AttributedString(image.plainText)
        default:
            return render(markup)
        }
    }
}
