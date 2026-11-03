// okay-remote's whole Swift: okay-faces' shell opening the phone's program by its class name (specs/ios.md).
// `-reset YES` (a test): the pairing forgotten first.
import SwiftUI
import OkayFaces

@main
struct RemoteApp: App {
  init() {
    let d = UserDefaults.standard
    if d.bool(forKey: "reset") {
      for k in d.dictionaryRepresentation().keys where k.hasPrefix("okay-") { d.removeObject(forKey: k) }
      SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                     kSecAttrService as String: "okay-faces"] as CFDictionary)
    }
  }
  var body: some Scene {
    WindowGroup { OkayFacesView(app: "okay.remote.phone.RemoteApp") }
  }
}
