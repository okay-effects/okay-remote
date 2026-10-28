# AGENTS.md — okay-remote

Read before anything else; then `specs/remote.md`. A change of design is a change of the spec first.

- Built on okay-guard (its `Os` on the JVM, its policy language, okay-ui for the page): `okay-guard-*` 0.1.0 from
  https://okay-effects.github.io/okay-guard/maven — after a change there, `sbt publishLibs` in okay-guard
  (republished as 0.1.0 while nobody else uses it; then clear the Coursier cache's copy). A gap in okay-guard or
  okay-ui is fixed there, never worked around here.
- What is a library's is there: the decision (okay-guard's `Judging`), the journal (okay-guard's `Ledger`), any
  agent (okay-guard-acp), the sealed rooms and the bot (okay-serverless's `relay`, `telegram`). okay-remote keeps its
  own: the sessions, the steps, the page, the relay's steward.
- The logic is `core/Remote`: programs over its `World` — `Files`, `Processes`, `Pages` and okay-scala's `Clock`,
  `Random`, `Log`, `Async` — run in a place by `World.run(ports)` (the JVM's ports in `daemon/` `OnJvm`, a test's
  `MemoryWorld`). No JDK I/O outside `daemon/`, no DOM outside okay-ui's `Dom.run`. The page is an okay-ui program
  (`web/Page`), its world the effect `Live`.
- Every tool call goes through the hook; the daemon unreachable, the call is refused (exit 2). Never a mode that
  skips it.
- The relay (`relay/`, a Cloudflare Worker) KEEPS NOTHING and READS NOTHING: no storage, frames sealed end to end;
  the pairing's secret only in the QR's fragment and the two ends' own storage. Never a key, a frame's text or a
  session in the relay.
- `sbt coreJVM/test`; `sbt fatJar` then `scripts/e2e/security.sh` (after any change to the daemon's server) and
  `node scripts/e2e/phone.js`; `sbt relayDist`, `npx wrangler dev --port 8799` in relay/, `node
  scripts/e2e/relay.js`, `node scripts/e2e/telegram.js` (a fake Bot API) (real Claude Haiku runs, the page at a
  phone's size, screenshots in /tmp/or-*.png). Deploy
  the relay: `relay/deploy.sh` (relay/.env, never committed). Lines at most 120 wide. Commits: plain messages, no
  attribution trailers.
