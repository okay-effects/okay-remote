# okay-remote

Your coding agents, on your machine, from your phone.

A daemon on your computer runs Claude Code in your projects — with Claude, or with a local model through
[rozum](https://github.com/sergey-scherbina/rozum). A page on your phone starts a task, shows the run as a
conversation, and answers what the policy asks. Every tool call of the agent is decided by an
[okay-guard](https://okay-effects.github.io/okay-guard/) policy before it is done: reading goes, writing in the
project goes, a command that is not one of the harmless ones waits for you, `sudo` never runs.

```text
sbt fatJar
java -jar dist/okay-remote.jar            # this machine only: open the address it prints
java -jar dist/okay-remote.jar --lan      # your phone on the same network, or over Tailscale: Phone shows a QR code
```

- **Projects**: the git repositories under `~/work` (or the directories listed in `~/.okay-remote/roots`, one a line).
- **Models**: Claude Sonnet, Opus, Haiku (Claude Code's own login), or a local model by `rozum launch`.
- **The policy**: `~/.okay-remote/policy.policy`, editable in the page, in okay-guard's
  [language](https://okay-effects.github.io/okay-guard/docs/policy.html); `cwd` is the project's directory.
- **Sessions** are kept in `~/.okay-remote/sessions/`: a page opened later sees the whole run, and a stopped session
  goes on (`--resume`) when you write to it.
- **The token** (`~/.okay-remote/token`) is in the address once; the page keeps it. Whoever has it runs agents on
  your machine.

How it works: [specs/remote.md](specs/remote.md). Built with [okay-effects](https://okay-effects.github.io/).
