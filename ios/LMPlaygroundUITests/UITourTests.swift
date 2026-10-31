import XCTest

/// Walks the main screens and saves a screenshot of each, for design review.
/// Runs only when SCREENSHOT_DIR is set (pass TEST_RUNNER_SCREENSHOT_DIR to
/// xcodebuild); needs Qwen 3.5 0.8B in the app's Documents/Models.
@MainActor
final class UITourTests: XCTestCase {

    private let modelName = "Qwen 3.5 0.8B"

    func testTour() throws {
        let dir = try XCTUnwrap(ProcessInfo.processInfo.environment["SCREENSHOT_DIR"].map { URL(filePath: $0) },
                                "SCREENSHOT_DIR not set")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var step = 0
        func shot(_ name: String) {
            step += 1
            let data = XCUIScreen.main.screenshot().pngRepresentation
            try? data.write(to: dir.appending(path: String(format: "%02d-%@.png", step, name)))
        }

        let app = XCUIApplication()
        app.launch()
        let title = app.buttons["modelPickerButton"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        shot("launch")

        app.buttons["menuButton"].tap()
        sleep(1)
        shot("drawer")
        // A tap on the pushed-aside chat closes the drawer.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        sleep(1)
        shot("drawer-closed")

        title.tap()
        sleep(1)
        shot("picker")
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", modelName)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        // A few frames: a small model loads in about a second.
        for _ in 0..<4 {
            usleep(200_000)
            shot("loading")
        }
        let loaded = NSPredicate(format: "label CONTAINS %@ AND NOT (label CONTAINS '%')", modelName)
        expectation(for: loaded, evaluatedWith: title)
        waitForExpectations(timeout: 60)
        sleep(1)
        shot("loaded")

        app.buttons["messageField"].tap()
        let field = app.textViews["messageField"].exists ? app.textViews["messageField"] : app.textFields["messageField"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        sleep(1)
        // The simulator's first-run keyboard tip.
        let tip = app.buttons["Continue"]
        if tip.exists { tip.tap() }
        shot("editor")
        field.typeText("What is the capital of France? One word.")
        shot("typed")
        app.buttons["sendButton"].tap()
        let stop = app.buttons["stopButton"]
        _ = stop.waitForExistence(timeout: 10)
        let done = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: stop)
        _ = XCTWaiter().wait(for: [done], timeout: 60)
        shot("reply")

        // Dismiss the keyboard: the input folds back to one line.
        app.swipeDown()
        sleep(1)
        shot("reply-collapsed")

        app.buttons["moreOptionsButton"].tap()
        sleep(1)
        shot("reply-menu")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        sleep(1)
        app.buttons["Downvote"].firstMatch.tap()
        sleep(1)
        shot("rating")
        app.buttons["Send"].firstMatch.tap()
        sleep(1)
        app.descendants(matching: .any)["userMessage"].firstMatch.press(forDuration: 1)
        sleep(1)
        shot("user-menu")
        app.buttons["Edit"].tap()
        sleep(1)
        shot("user-editor")
        app.buttons["Cancel"].tap()
        sleep(1)
        app.swipeDown()
        sleep(1)

        title.tap()
        sleep(1)
        shot("picker-loaded")
        // A tap on the dimmed chat closes the picker.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)).tap()
        sleep(1)

        app.buttons["menuButton"].tap()
        sleep(1)
        shot("drawer-with-chat")
    }
}
