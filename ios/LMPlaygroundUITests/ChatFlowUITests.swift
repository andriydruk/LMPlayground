import XCTest

/// End-to-end chat on a device. Needs Qwen 3.5 0.8B
/// (Qwen_Qwen3.5-0.8B-IQ4_XS.gguf) in the app's Documents/Models.
@MainActor
final class ChatFlowUITests: XCTestCase {

    private let modelName = "Qwen 3.5 0.8B"

    override func setUp() {
        continueAfterFailure = false
    }

    private func launchWithModel() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        let picker = app.buttons["modelPickerButton"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        if !picker.label.contains(modelName) {
            picker.tap()
            let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", modelName)).firstMatch
            XCTAssertTrue(item.waitForExistence(timeout: 5), "\(modelName) is not downloaded")
            item.tap()
        }
        // Loaded when the picker shows the model's name.
        let loaded = NSPredicate(format: "label CONTAINS %@", modelName)
        expectation(for: loaded, evaluatedWith: picker)
        waitForExpectations(timeout: 60)

        // The thinking toggle is in the editor, which opens on a tap.
        let collapsed = app.buttons["messageField"]
        if collapsed.exists { collapsed.tap() }
        let thinking = app.buttons["thinkingToggle"]
        if thinking.waitForExistence(timeout: 3), thinking.label == "Thinking on" { thinking.tap() }
        return app
    }

    private func send(_ text: String, in app: XCUIApplication) {
        // Idle, the input is one line that opens the editor when tapped.
        let collapsed = app.buttons["messageField"]
        if collapsed.exists { collapsed.tap() }
        let field = app.textFields["messageField"].exists ? app.textFields["messageField"] : app.textViews["messageField"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        if !field.hasFocus { field.tap() }
        field.typeText(text)
        app.buttons["sendButton"].tap()
    }

    private func waitForGone(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }

    func testAnswersAQuestion() {
        let app = launchWithModel()
        app.buttons["New chat"].firstMatch.tap()
        send("What is the capital of France? Answer with one word.", in: app)

        let answer = app.descendants(matching: .any)["assistantAnswer"].firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 60))
        let paris = NSPredicate(format: "label CONTAINS[c] 'paris'")
        expectation(for: paris, evaluatedWith: answer)
        waitForExpectations(timeout: 60)
        XCTAssertTrue(waitForGone(app.buttons["stopButton"], timeout: 30), "generation never finished")
    }

    private func waitForAnswer(containing text: String, in app: XCUIApplication) {
        let answer = app.descendants(matching: .any)["assistantAnswer"].firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 60))
        expectation(for: NSPredicate(format: "label CONTAINS[c] %@", text), evaluatedWith: answer)
        waitForExpectations(timeout: 60)
        XCTAssertTrue(waitForGone(app.buttons["stopButton"], timeout: 30), "generation never finished")
    }

    /// Editing replaces the turn and what followed it; regenerate and branch
    /// keep working on the result.
    func testMessageActions() {
        let app = launchWithModel()
        app.buttons["New chat"].firstMatch.tap()
        send("What is the capital of France? Answer with one word.", in: app)
        waitForAnswer(containing: "paris", in: app)

        let bubble = app.descendants(matching: .any)["userMessage"].firstMatch
        bubble.press(forDuration: 1)
        app.buttons["Edit"].tap()
        let editor = app.textFields["messageEditor"].exists ? app.textFields["messageEditor"] : app.textViews["messageEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.press(forDuration: 1.2)
        app.menuItems["Select All"].tap()
        editor.typeText("What is the capital of Japan? Answer with one word.")
        app.buttons["editorSendButton"].tap()
        waitForAnswer(containing: "tokyo", in: app)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "userMessage").count, 1)

        app.buttons["moreOptionsButton"].tap()
        app.buttons["Regenerate"].tap()
        waitForAnswer(containing: "tokyo", in: app)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "assistantAnswer").count, 1)

        app.buttons["moreOptionsButton"].tap()
        app.buttons["Branch in new chat"].tap()
        XCTAssertTrue(app.staticTexts["Continuing in a new chat"].waitForExistence(timeout: 10))
        app.buttons["sessionInfoButton"].tap()
        XCTAssertTrue(app.alerts["Session Info"].waitForExistence(timeout: 5))
        app.alerts.buttons["Close"].tap()

        // The branch keeps its context.
        send("Which country was that about? Answer with one word.", in: app)
        let answers = app.descendants(matching: .any).matching(identifier: "assistantAnswer")
        expectation(for: NSPredicate(format: "count >= 2"), evaluatedWith: answers)
        waitForExpectations(timeout: 60)
    }

    /// iPhone apps can't use the GPU in the background: the reply stops and
    /// is marked interrupted, and the app keeps working.
    func testBackgroundingInterruptsTheReply() {
        let app = launchWithModel()
        app.buttons["New chat"].firstMatch.tap()
        send("Count from 1 to 400, one number per line.", in: app)
        XCTAssertTrue(app.buttons["stopButton"].waitForExistence(timeout: 30))
        sleep(2)

        XCUIDevice.shared.press(.home)
        sleep(3)
        app.activate()

        XCTAssertTrue(app.descendants(matching: .any)["interruptedLabel"].waitForExistence(timeout: 30))
        XCTAssertTrue(waitForGone(app.buttons["stopButton"], timeout: 10))

        // The engine still works after the interruption.
        send("Say OK.", in: app)
        let answers = app.descendants(matching: .any).matching(identifier: "assistantAnswer")
        let two = NSPredicate(format: "count >= 2")
        expectation(for: two, evaluatedWith: answers)
        waitForExpectations(timeout: 60)
    }
}
