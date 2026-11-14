import XCTest

/// A chat opens at its end, whichever chat was open before and wherever it
/// was scrolled to. Uses the seeded scroll test chats; needs no model.
@MainActor
final class ChatScrollUITests: XCTestCase {

    private let long = "Scroll test: long"
    private let short = "Scroll test: short"

    override func setUp() {
        continueAfterFailure = false
    }

    func testOpeningAChatShowsItsEnd() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiSeedChats", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["menuButton"].waitForExistence(timeout: 10))

        open(long, in: app)
        assertEndShown("End of long chat", in: app)
        open(short, in: app)
        assertEndShown("End of short chat", in: app)
        open(long, in: app)
        assertEndShown("End of long chat", in: app)

        // Scrolled up, the way back to the end is offered.
        app.swipeDown(velocity: .fast)
        app.swipeDown(velocity: .fast)
        XCTAssertTrue(app.buttons["jumpToBottom"].waitForExistence(timeout: 3))
        open(short, in: app)
        assertEndShown("End of short chat", in: app)
        open(long, in: app)
        assertEndShown("End of long chat", in: app)

        // Far up the long chat, then back down.
        for _ in 0..<6 { app.swipeDown(velocity: .fast) }
        app.buttons["jumpToBottom"].tap()
        assertEndShown("End of long chat", in: app)
    }

    /// A thinking section opens and closes under its header, however long
    /// it is: the header stays under the finger and the chat doesn't scroll.
    func testThinkingStaysInPlace() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiSeedChats", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["menuButton"].waitForExistence(timeout: 10))
        open(long, in: app)
        assertEndShown("End of long chat", in: app)

        // At the end of the chat, then scrolled up a little.
        toggleLowestThinking(in: app)
        app.swipeDown(velocity: .slow)
        sleep(1)
        toggleLowestThinking(in: app)
    }

    /// Older messages join on the way up, without the chat jumping away
    /// from where it was scrolled to.
    func testScrollingUpReachesTheFirstMessage() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiSeedChats", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["menuButton"].waitForExistence(timeout: 10))
        open(long, in: app)
        assertEndShown("End of long chat", in: app)

        // Question numbers on screen fall steadily: a page joining doesn't
        // send the chat back down or up to its first message.
        let questions = app.descendants(matching: .any).matching(identifier: "userMessage")
        func lowest() -> Int {
            questions.allElementsBoundByIndex.filter { $0.frame.minY > 0 && $0.frame.maxY < app.frame.maxY }
                .compactMap { $0.label.firstMatch(of: /Question (\d+)/).flatMap { Int($0.1) } }.min() ?? .max
        }
        var last = lowest()
        var swipes = 0
        while last > 1 && swipes < 60 {
            app.swipeDown(velocity: .fast)
            swipes += 1
            sleep(1)
            shot("up")
            let now = lowest()
            XCTAssertLessThanOrEqual(now, last, "the chat jumped down after \(swipes) swipes")
            last = now
        }
        XCTAssertEqual(last, 1, "the first message was not reached")
    }

    private func toggleLowestThinking(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let top = app.navigationBars.firstMatch.frame.maxY
        let bottom = app.buttons["messageField"].frame.minY
        // The headers are all alike, and more rows load as one opens: go by
        // where the header is.
        let query = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Thought for'"))
        let shown = query.allElementsBoundByIndex.filter { $0.frame.minY > top && $0.frame.maxY < bottom }
        guard let lowest = shown.max(by: { $0.frame.minY < $1.frame.minY }) else {
            return XCTFail("no thinking on screen", file: file, line: line)
        }
        let place = lowest.frame.minY
        func header() -> XCUIElement? {
            query.allElementsBoundByIndex.first { abs($0.frame.minY - place) <= 2 }
        }
        lowest.tap()
        sleep(2)
        shot("thinking-open")
        guard let opened = header() else {
            let found = query.allElementsBoundByIndex.map(\.frame.minY)
            return XCTFail("opening moved the chat: \(place) → \(found)", file: file, line: line)
        }
        opened.tap()
        sleep(2)
        shot("thinking-closed")
        let found = query.allElementsBoundByIndex.map(\.frame.minY)
        XCTAssertNotNil(header(), "closing moved the chat: \(place) → \(found)", file: file, line: line)
    }

    private var step = 0

    /// Saves the screen when SCREENSHOT_DIR is set (TEST_RUNNER_SCREENSHOT_DIR
    /// to xcodebuild).
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] else { return }
        step += 1
        let url = URL(filePath: dir).appending(path: String(format: "scroll-%02d-%@.png", step, name))
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    private func open(_ title: String, in app: XCUIApplication) {
        app.buttons["menuButton"].tap()
        let row = app.buttons[title]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        // The drawer closes.
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == false"), object: row)
        _ = XCTWaiter().wait(for: [gone], timeout: 3)
    }

    private func assertEndShown(_ text: String, in app: XCUIApplication, file: StaticString = #filePath,
                                line: UInt = #line) {
        let end = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        XCTAssertTrue(end.waitForExistence(timeout: 3), "\(text) not loaded", file: file, line: line)
        shot(text)
        // Between the navigation bar and the input (isHittable is false for
        // the text of a reply).
        let top = app.navigationBars.firstMatch.frame.maxY
        let bottom = app.buttons["messageField"].frame.minY
        // Just above the input, with only the reply's actions in between.
        func shown() -> Bool {
            end.frame.minY >= top && end.frame.maxY <= bottom && bottom - end.frame.maxY < 120
        }
        XCTAssertTrue(shown(), "\(text) not on screen at \(end.frame)", file: file, line: line)
        // Settled at the end: no offer to jump there.
        sleep(1)
        XCTAssertTrue(shown(), "\(text) scrolled away to \(end.frame)", file: file, line: line)
        XCTAssertFalse(app.buttons["jumpToBottom"].exists, "not at the end", file: file, line: line)
    }
}
