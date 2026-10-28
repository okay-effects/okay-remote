# okay-remote

Your coding agents, on your machine, from your phone — from anywhere, safely.

A daemon on your computer runs Claude Code in your projects — with Claude, or with a local model through
[rozum](https://github.com/sergey-scherbina/rozum). A page on your phone starts a task, shows the run as a
conversation, shows what changed, and answers what the policy asks. Every tool call of the agent is decided by an
[okay-guard](https://okay-effects.github.io/okay-guard/) policy before it is done: reading goes, writing in the
project goes, a command that is not one of the harmless ones waits for you, `sudo` never runs.

## Start

You need Java 21+ and [Claude Code](https://claude.com/claude-code) (logged in); for local models and the sandbox,
[rozum](https://github.com/sergey-scherbina/rozum) (macOS).

```text
java -jar okay-remote.jar --relay      # then open the address it prints, and Phone: scan the QR code
```

The phone opens the page from the relay and is paired: from then on it reaches your computer from any network. The
daemon dials out to the relay; no port is opened on your machine. `--relay <url>` for your own relay (`relay/`, a
Cloudflare Worker: `relay/deploy.sh`); `--lan` instead, for the same network only; neither, this machine only.

- **Projects**: the git repositories under `~/work` (or the directories in `~/.okay-remote/roots`, one a line).
- **Models**: Claude Sonnet, Opus, Haiku, or a local model through `rozum launch`.
- **The policy**: `~/.okay-remote/policy.policy`, editable on the phone, in okay-guard's
  [language](https://okay-effects.github.io/okay-guard/docs/policy.html); **Always allow** on a waiting call adds
  the rule for it.
- **Sessions** are kept in `~/.okay-remote/sessions/`; a stopped one goes on when you write to it.

## Safe, in layers

1. **The policy decides every tool call**, through Claude Code's PreToolUse hook. If the daemon cannot be reached,
   the call is not done.
2. **The sandbox** (rozum, macOS): local models always run jailed — writes only in the project and the toolchains'
   caches, secrets unreadable, network to the local model only. Claude runs jailed too when
   `~/.okay-remote/claude-token` holds a token from `claude setup-token`.
3. **The way in**: on the machine, 127.0.0.1 and a token (Host and Origin checked). Through the relay, every frame is
   sealed end to end (AES-256-GCM) by a key derived from the pairing's secret, which travels only in the QR code's
   fragment; the relay keeps nothing and cannot read what it passes; frames out of time, replayed or reflected are
   dropped. `--pair-again` shuts every paired phone out.
4. **The record**: every step of every session, each line chained to the one before by SHA-256:
   `java -jar okay-remote.jar verify`.

How it works: [specs/remote.md](specs/remote.md). Built with [okay-effects](https://okay-effects.github.io/).

## Build

`sbt fatJar` (the daemon, `dist/okay-remote.jar`), `sbt relayDist` (the relay, `relay/dist`). Tests: `sbt
coreJVM/test`; `scripts/e2e/security.sh`; `node scripts/e2e/phone.js`, `node scripts/e2e/relay.js` (real Claude
Haiku runs, a phone's screen in headless Chrome).
