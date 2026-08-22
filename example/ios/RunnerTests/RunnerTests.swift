import Flutter
import UIKit
import XCTest


@testable import geofencing_service

// This demonstrates a simple unit test of the Swift portion of this plugin's implementation.
//
// See https://developer.apple.com/documentation/xctest for more information about using XCTest.

class RunnerTests: XCTestCase {

  func testGetRegisteredGeofenceIds() {
    let plugin = FlutterGeofencePlugin()

    let call = FlutterMethodCall(methodName: "getRegisteredGeofenceIds", arguments: nil)

    let resultExpectation = expectation(description: "result block must be called.")
    plugin.handle(call) { result in
      XCTAssertNotNil(result as? [String])
      resultExpectation.fulfill()
    }
    waitForExpectations(timeout: 1)
  }

}
