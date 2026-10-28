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
phone (PWA) ──HTTP+SSE──▶ the daemon (JVM) ──spawns──▶ claude -p (stream-json), in a project
                               ▲                         │  (or: rozum launch --model … -- claude …)
                               └── PreToolUse hook ◀─────┘  every tool call, decided by the policy
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
  `allow` → the call goes; `deny` → it does not, the agent told why; `ask` → the phone is asked, the hook waits
  for the answer (10 minutes, then no). The call's arguments are the tool's input, and `cwd`, the project's directory.
  The policy is `~/.okay-remote/policy.policy`, editable in the page; a default ships (`core/Defaults`).
- **The page** (`web/`, an okay-ui program, like okay-guard's studio): Projects → a session as a conversation (your
  words, the agent's, tool cards with the decision's mark, a card waiting for you with Approve and Refuse), a box for
  the next words, Stop. New: the project, the model, the task. Policy: its text. Installable (manifest, service
  worker), phone first.

## Anywhere: the relay (2026-10-28)

The phone reaches the daemon from any network, with no port open on the machine and nothing readable on the way:

```text
phone (the page, from the relay) ──wss──▶ Relay (Cloudflare Worker + a Durable Object per room, memory only)
                                                    ▲
                     the daemon ──wss (out, it dials)──┘      every frame AES-256-GCM, end to end
```

- **Pairing**: the daemon makes a secret S (32 random bytes, `~/.okay-remote/relay.json`, 0600). Its local page
  (127.0.0.1 only) shows a QR code of `<relay>/#s=<S>`: the fragment never leaves the phone. The page keeps S in the
  browser. `--pair-again` makes a new S: every paired phone is out.
- **Derived**: `enc = SHA-256("okay-remote/enc:" ‖ S)` the AES key; `auth = hex SHA-256("okay-remote/auth:" ‖ S)`;
  `room = hex SHA-256(auth)`. Both ends dial `/r/<room>/{daemon|phone}?auth=<auth>`; the relay lets in only an
  `auth` whose SHA-256 is the room (okay-guard's meeting points' way). The relay never has `enc`: it passes frames it
  cannot read, keeps nothing (no storage; the sockets' roles in their attachments), one daemon per room (the newest),
  any number of phones, frames at most 2 MB.
- **A frame**: `base64url(iv ‖ AES-GCM(enc, iv, json, aad "okay-remote/1"))`; the JSON says who sent it (`from`),
  when (`t`, ms) and a nonce (`n`). A frame from the wrong side, older than 5 minutes, or seen before, is dropped —
  no reflection, no replay. Kinds: phone → `hello`, `act {id, body}`; daemon → `answer {id, ok, body}`, `event
  {name, data}`.
- The page is the same program: its `Live` over a transport — HTTP+SSE on the machine (the token), or the relay
  (the secret). The hosted relay is `https://okay-remote.okay-effects.workers.dev`; anyone can deploy their own
  (`relay/`, `--relay <url>`).

## Safety, in layers

1. **The policy decides every tool call** (the hook; fail closed). It is the boundary for every model.
2. **The sandbox** (rozum's Seatbelt jail, macOS): writes only in the project and the toolchains' caches, secrets
   unreadable, network loopback only. Local models always run in it. Claude runs in it when rozum is there and
   `~/.okay-remote/claude-token` holds a token from `claude setup-token` (the jail keeps the keychain closed, so
   Claude's own login cannot be read in it); network then full (Claude's API), writes still confined.
3. **The way in**: 127.0.0.1 and a token (Host and Origin checked, constant-time compare); or the relay, end to end
   encrypted, the room's auth checked by the relay, the frames by the daemon.
4. **The record**: every step of every session kept, each line chained to the one before by SHA-256 (`h`), so a
   changed or removed line is found (`okay-remote verify`).

## Working with it

- **Always allow**: on a card that waits, a rule made from the call (a command exactly; a file's directory; a tool),
  added to the policy — only if, added, it really allows this call (an `ask` still wins over an `allow`: the page
  says which rule asks).
- **Changes**: a session's project's `git status` and `git diff`, on the phone.

## Later

- Notifications (Web Push) when a call waits; diffs of the files changed; several machines.
