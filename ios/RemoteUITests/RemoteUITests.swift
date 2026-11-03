// OKAY-REMOTE DRIVEN as a person would, on the simulator (specs/ios.md): paired by a link, as a pasted one is,
// against a real daemon through a real relay — `OKAY_REMOTE_PAIR=<link>` (TEST_RUNNER_OKAY_REMOTE_PAIR to xcodebuild).
// Each step's screen kept in /tmp/okay-remote-ios/.
import XCTest

final class RemoteUITests: XCTestCase {
  override func setUp() { continueAfterFailure = false }

  func shot(_ name: String) {
    let png = XCUIScreen.main.screenshot().pngRepresentation
    try? FileManager.default.createDirectory(atPath: "/tmp/okay-remote-ios", withIntermediateDirectories: true)
    try? png.write(to: URL(fileURLWithPath: "/tmp/okay-remote-ios/\(name).png"))
  }

  func testPairedThroughTheRelay() throws {
    let link = try XCTUnwrap(ProcessInfo.processInfo.environment["OKAY_REMOTE_PAIR"], "OKAY_REMOTE_PAIR: a link")
    let app = XCUIApplication()
    app.launchArguments = ["-reset", "YES"]
    app.launch()
    XCTAssert(app.staticTexts["not paired"].waitForExistence(timeout: 10))
    shot("1-unpaired")
    let field = app.textFields["field-or-paste-a-pairing-link"]
    field.tap(); field.typeText(link)
    app.buttons["act-pair"].tap()
    XCTAssert(app.staticTexts["connected"].waitForExistence(timeout: 30), "the daemon reached through the relay")
    shot("2-connected")
    app.buttons["ref-places/policy"].tap()
    XCTAssert(app.staticTexts["connected"].waitForExistence(timeout: 10))
    shot("3-policy")
    app.buttons["ref-places/sessions"].tap()
    sleep(2)
    shot("4-sessions")
    // taken up again: the pairing kept in the keychain, the room dialled at launch
    app.terminate()
    app.launchArguments = []
    app.launch()
    XCTAssert(app.staticTexts["connected"].waitForExistence(timeout: 30), "paired after a restart")
    shot("5-again")
  }
}
