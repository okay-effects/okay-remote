# AGENTS.md — okay-remote

Read before anything else; then `specs/remote.md`. A change of design is a change of the spec first.

- Built on okay-guard (its `Os` on the JVM, its policy language, okay-ui for the page): `okay-guard-*`
  0.1.0-SNAPSHOT from `~/.ivy2/local` — `sbt coreJVM/publishLocal coreJS/publishLocal policyJVM/publishLocal
  policyJS/publishLocal httpJVM/publishLocal localJVM/publishLocal jvm/publishLocal` in okay-guard after a change
  there. A gap in okay-guard or okay-ui is fixed there, never worked around here.
- The logic is `core/Remote` over a `Machine` (the JVM's in `daemon/`, a test's in memory): no JDK I/O outside
  `daemon/`, no DOM outside okay-ui's `Dom.run`. The page is an okay-ui program (`web/Page`), its world the effect
  `Live`.
- Every tool call goes through the hook; the daemon unreachable, the call is refused (exit 2). Never a mode that
  skips it.
- `sbt coreJVM/test`; `sbt fatJar` then `node scripts/e2e/phone.js` (a real Claude Haiku run, the page at a phone's
  size, screenshots in /tmp/or-*.png). Lines at most 120 wide. Commits: plain messages, no attribution trailers.
