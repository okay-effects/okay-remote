# okay-remote on an iPhone, native (spec, 2026-11-03)

Asked: «нативное с бесплатным и сайдстором. только мне нужна полноценная нативная версия а не просто веб вью»,
then «ты должен использовать okay-faces okay-ui».

## What it is

The phone's okay-remote is THE SAME okay-ui PROGRAM as the page (`Page.main`: the sessions, a session as a
conversation, its calls with Approve / Refuse / Always allow, New, the policy), run by okay-faces' mobile face —
Scala Native under okay-faces' SwiftUI shell (okay-faces' specs/mobile.md), drawn natively, no web view. It reaches
the daemon through the relay, sealed end to end, as the page does.

```
Page.main  (okay-ui: Ui + Live)          ── the page's program, unchanged in what it says
  Live     handled by PhoneRelay         ── the relay's guest over the phone: a socket, AES-GCM, the keychain
  Ui       Business.steps → OnFaces      ── okay-ui's steps as okay-faces' Face
  Face     MobileFace                    ── the screen as JSON to SwiftUI, the choice back
```

## The parts

1. **okay-faces, the phone gets three powers** (its specs/mobile.md, "Sockets, sealing, scanning"):
   - `Socket`: `{"op": "socket", "url"}` opens a WebSocket, answers `{"id"}`; `socketSend {id, text}`,
     `socketClose {id}`; what comes on it — a text, its closing — comes back by itself as a DEVICE EVENT
     (`{"event": "socket", "id", "text"}`, `{"event": "closed", "id", "why"}`), given to the library by a new C
     function `okay_faces_device_event(const char*)`; the library hands it to whoever listens (`DeviceEvents`), and a
     step waiting on it moves the dialogue (`moved`).
   - `Seal` / `Unseal`: AES-256-GCM by a key (base64url), associated data, a random IV — `iv ‖ ciphertext ‖ tag`,
     base64url, as okay-serverless' `Sealer` frames it (CryptoKit on an iPhone, JCA in `Device.Memory`).
   - `Scan`: a QR code read by the camera, its text (AVFoundation's metadata output).
   Android answers them too (OkHttp's WebSocket is not in the platform: the java one through `java.net` sockets is
   not either — Android answers `socket` with an error until a shell adds a client; `seal`, `unseal` by
   javax.crypto, `scan` by ML Kit's barcode scanner — later).
2. **okay-ui on Scala Native**: `core`, `scala3`, `faces` cross-built for Native (pure Scala: no change of code).
3. **okay-serverless' relay, its shared part on Native**: `Pairing`, `Hashes`, `Envelope`, `Tokens`.
4. **okay-remote**:
   - `model` (JVM, JS, Native): `Session`, `Step`, `J` — what the page and the phone read; `core` depends on it.
   - `page` (JVM, JS, Native): `Page` (its one Scala.js word, the clock, through `Live`), `Live`, `Transport`.
   - `phone` (JVM for its tests, Native for the phone): `PhoneRelay` — the guest at the sealed room over the device's port: the socket, `seal`,
     `unseal`, the pairing's secret in the keychain (`hide`/`secret`), pairing by a scanned QR code or a pasted link;
     the frames out of time or seen before dropped (`Envelope`), as the page's `Guest` does. `RemoteApp extends
     MobileApp`: `MobileFace` over `steps` of `Live.Over(PhoneRelay)(Page.main)`. A closed socket dialled again once, until the room
     is reached again; the unpaired screen offers "Scan the QR code" where the transport has a camera (`scanner`).
   - `ios/`: the app (okay-faces' template: `project.yml` for xcodegen, the shell's one line naming
     `okay.remote.phone.RemoteApp`, an icon), built by `scripts/ios.sh [lib|app|ipa]` into `ios/lib/OkayRemote.xcframework` (okay-faces checked out
     beside, ../okay-faces: the Swift package).
5. **Installing with a free Apple ID**: an UNSIGNED `.ipa` (`xcodebuild archive … CODE_SIGNING_ALLOWED=NO`,
   `Payload/okay-remote.app` zipped) — SideStore signs it with the person's Apple ID and renews it every 7 days over
   Wi-Fi. No account on the Mac.

## What a free Apple ID does not give

Push notifications (APNs needs a paid team): the app is told nothing while it is closed — Telegram tells as before;
while it is open, a call that waits shows at once (the socket). A local notification while it is in the background
and still alive: not yet.

## Checked (2026-11-03)

- `PhoneSuite` (JVM): the page's program over `PhoneRelay` with `Device.Memory` and an in-process room sealing as
  the daemon does: paired, the daemon's hello its sessions, a session opened by an act and its answer; frames of
  another pairing dropped; offline shown; a pasted link and a scanned QR code pair (kept in the keychain), a QR code
  that is not a pairing said so.
- found on the way, fixed in okay-faces (`Session`): an event heard at once as a step listens was lost, and a screen
  could show one step with the actions of the next (`EventsSuite`).
- the shell on the simulator (`RemoteUITests`, `TEST_RUNNER_OKAY_REMOTE_PAIR=<link> xcodebuild test`): paired by
  the link against the real daemon through the hosted relay, connected, the sessions and the policy shown, paired
  again after a restart (the keychain).
- a device build unsigned (arm64, iOS) and `dist/okay-remote.ipa` made (1.3 MB).
- not yet: a call approved by a tap on the simulator (a test daemon with a waiting call), the look of the page's
  marks on the phone (`top`, `places`, rows: plain text today), a phone-only wording of the unpaired screen.
