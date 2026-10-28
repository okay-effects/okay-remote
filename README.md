# okay-remote

Your coding agents, on your machine, from your phone — from anywhere, safely.

okay-remote runs on your computer. It starts a coding agent — Claude Code, Qwen Code, Gemini CLI, opencode, Goose,
… — in one of your projects, and a page on your phone shows the run as a conversation: what the agent says, every
file it writes and every command it runs, what changed. Every action of the agent is first decided by a policy: it
goes, it waits for you (Approve / Refuse on the phone), or it is refused. The agent works with Claude, or with a
model on your own computer through [rozum](https://github.com/sergey-scherbina/rozum).

## Install (macOS or Linux)

1. **Java 17 or newer.** Check: `java -version`. If it is missing: `brew install openjdk@21` on a Mac (then follow
   what brew prints to put it on your PATH), or a JDK from https://adoptium.net.
2. **A coding agent, logged in.** The usual one is Claude Code: https://claude.com/claude-code — install it and run
   `claude` once to log in. Others are found by themselves if installed: `qwen`, `gemini`, `opencode`, `goose`, and
   Claude Code or Codex through ACP (`npx` needed).
3. **okay-remote:**

   ```sh
   curl -fsSL https://raw.githubusercontent.com/okay-effects/okay-remote/main/install.sh | sh
   ```

   It puts the program in `~/.okay-remote/bin` and the command `okay-remote` in `~/.local/bin` (it tells you if that
   is not on your PATH). Nothing is installed as root. Or download `okay-remote.jar` from the
   [releases](https://github.com/okay-effects/okay-remote/releases) and run `java -jar okay-remote.jar` yourself.

## Start, and pair your phone

```sh
okay-remote --relay
```

It prints an address, `http://127.0.0.1:7788/?t=…`. Open it in a browser on this computer, go to **Phone**, and scan
the QR code with your phone's camera. The phone opens the page and is paired: from then on it reaches this computer
from any network. On an iPhone, Share → **Add to Home Screen** makes it an app.

To start okay-remote with every login, so the phone can always reach it while the computer is awake:

```sh
okay-remote service install      # okay-remote service status | okay-remote service uninstall
```

## Work

- **New**: a project, an agent, a task. **Projects** are the git repositories under `~/work`; to choose others, list
  their directories (or the directories that hold them) in `~/.okay-remote/roots`, one a line.
- **The session**: the agent's words, and a card for each call, marked allowed, waiting, denied. A card that waits
  has **Approve**, **Always allow** (a rule for calls like it is added to the policy) and **Refuse**. **Next** sends
  more words; **Changes** shows `git status` and `git diff`; **Stop** stops the agent (writing to it again starts it
  again, where it was); **Forget** removes a stopped session.
- **Policy**: what the agent may do, in [okay-guard's language](https://okay-effects.github.io/okay-guard/docs/policy.html).
  The one it starts with: reading goes; writing in the project goes; a few harmless commands (`ls`, `git status`,
  `git diff`, `sbt`, `cargo`, …) go; chains of commands, `git push`, the web, and anything else wait for you; `sudo`
  never runs.

## A model on your own computer

Install [rozum](https://github.com/sergey-scherbina/rozum) (macOS, Apple Silicon; its INSTALL.md) and pull a model
(`rozum models pull …`). The agents **Local · …** on New then run Claude Code on that model, inside rozum's sandbox,
with no cloud and no key. With rozum installed, the commands of the other agents run in its sandbox too.

## Safe, in layers

1. **The policy decides every action** before it is done. Claude Code is held by its PreToolUse hook (every tool
   call); the other agents by ACP: the files they write and the commands they run are written and run by okay-remote
   itself, after the policy, and what they do themselves they must ask first. Each agent is started in the mode in
   which it asks. If okay-remote cannot be reached, the action is not done.
2. **The sandbox** (rozum, macOS): writes only in the project and the toolchains' caches, secrets unreadable. Local
   models always run in it; Claude too if `~/.okay-remote/claude-token` holds a token from `claude setup-token`.
3. **The way in**: on the computer, 127.0.0.1 and a token. Through the relay, every message is encrypted end to end
   (AES-256-GCM) with a key from the QR code, which never leaves your devices: the relay keeps nothing and cannot read
   what it passes; old, replayed or reflected messages are dropped. Lost your phone, or showed the code to someone?
   `okay-remote --relay --pair-again` shuts every paired phone out.
4. **The record**: every step of every session is kept in `~/.okay-remote/sessions/`, each line chained to the one
   before by SHA-256: `okay-remote verify` finds a line changed or removed.

## When something is wrong

- *The phone says "the machine is not reached"*: okay-remote is not running, or the computer sleeps. Start it, or
  `okay-remote service install`.
- *A new task fails at once*: run the agent by hand in the project (`claude`, `qwen`, …): it may need a login.
- *No projects*: put your projects' directories in `~/.okay-remote/roots`.
- *The log*: `~/.okay-remote/daemon.log` when it runs as a service; the terminal otherwise.

## Uninstall

`okay-remote service uninstall`, then `rm -rf ~/.okay-remote ~/.local/bin/okay-remote` (that removes the sessions and
the policy too).

## For developers

How it works: [specs/remote.md](specs/remote.md). Built with [okay-effects](https://okay-effects.github.io/) on
[okay-guard](https://okay-effects.github.io/okay-guard/). You need Java 17+ and [sbt](https://www.scala-sbt.org/);
`sbt fatJar` builds `dist/okay-remote.jar` (the libraries come from their Maven repositories on GitHub Pages,
okay-guard's at https://okay-effects.github.io/okay-guard/maven). The relay is `relay/` (a
Cloudflare Worker): `sbt relayDist`, then `relay/deploy.sh`, and `okay-remote --relay <its URL>`. Tests: `sbt
coreJVM/test`, `scripts/e2e/security.sh`, `node scripts/e2e/phone.js`, `node scripts/e2e/relay.js`, `node
scripts/e2e/agents.js "<agent>"`.

Apache License 2.0.
