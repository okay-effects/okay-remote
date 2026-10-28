# okay-remote (spec)

Your coding agents, on your machine, from your phone. A daemon on the machine runs the agent — Claude Code, with
Claude or with a local model through rozum — in your projects; a page on the phone (a PWA) starts a task, shows the
run as a conversation, and answers what the policy asks. Every action of the agent is decided by an okay-guard policy
before it is done.

## Why

Claude Code's own remote needs claude.ai and Anthropic's servers; a local model behind `ANTHROPIC_BASE_URL` cannot use
it. okay-remote needs neither: the agent and the model are the machine's, the page is ours.

## The parts

```text
phone (PWA) ──HTTP+SSE──▶ okay-remote (the daemon, JVM) ──spawns──▶ claude -p (stream-json in and out), in a project
                               ▲                                      │  (or: rozum launch --model … -- claude …)
                               └──── PreToolUse hook (curl) ◀─────────┘  every tool call, decided by the policy
```

- **The daemon** (`daemon/`, `okay-remote.jar`): the projects, the sessions, the policy, the page. Over okay-guard's
  `Os` (`JvmOs`): its processes, its web server, its files. Binds 127.0.0.1; `--lan` binds every address so a phone
  on the same network (or Tailscale) reaches it. A token on every request; the page keeps it.
- **A session**: one `claude -p --input-format stream-json --output-format stream-json --verbose` process in the
  project's directory, kept while the session is open: a follow-up is one more line to it. Its lines become **steps**
  (`core/Steps`): what the agent says, each tool call, its result, the end of a turn (time, cost). Steps are kept in
  `~/.okay-remote/sessions/<id>.jsonl`, so a page opened later sees the whole run. `--resume <claude's session id>`
  carries a session over a restart of the daemon.
- **The model**: Claude (`--model opus|sonnet|haiku`), or a local one: `rozum launch --offline --model <spec> --
  claude …` (rozum's gateway, its sandbox). `--permission-mode default` keeps rozum from adding
  `--dangerously-skip-permissions`; the hook decides either way.
- **The policy**: Claude Code's `PreToolUse` hook (given with `--settings`, matcher `*`) posts each tool call to the
  daemon (`/hook/<session>`), which decides it by an okay-guard policy (okay-policy, the language of okay-guard):
  `allow` → the call goes; `deny` → it does not, the agent told why; `ask` → the phone is asked, the hook waits for
  the answer (10 minutes, then no). The call's arguments are the tool's input, and `cwd`, the project's directory.
  The policy is `~/.okay-remote/policy.policy`, editable in the page; a default ships (`core/Defaults`).
- **The page** (`web/`, an okay-ui program, like okay-guard's studio): Projects → a session as a conversation (your
  words, the agent's, tool cards with the decision's mark, a card waiting for you with Approve and Refuse), a box for
  the next words, Stop. New: the project, the model, the task. Policy: its text. Installable (manifest, service
  worker), phone first.

## Later

- **The relay** (no open ports, any network): a Cloudflare Worker meeting point as okay-guard's (keeps nothing), the
  daemon connected out to it, the phone to it; end-to-end encrypted by a key given by a QR code.
- Notifications (Web Push) when a call waits; diffs of the files changed; several machines.
