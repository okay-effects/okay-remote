#!/bin/sh
# okay-remote's installer: the release's jar in ~/.okay-remote/bin, the command `okay-remote` in ~/.local/bin.
#   curl -fsSL https://raw.githubusercontent.com/okay-effects/okay-remote/main/install.sh | sh
# OKAY_REMOTE_VERSION=0.1.0 for a given release (the latest by default). Nothing is run as root.
set -eu
repo=okay-effects/okay-remote
version=${OKAY_REMOTE_VERSION:-latest}
bin="$HOME/.okay-remote/bin"
cmd_dir="$HOME/.local/bin"

say() { printf '%s\n' "$*"; }
fail() { printf 'okay-remote: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || fail "curl is needed"
command -v java >/dev/null || fail "Java 17 or newer is needed: brew install openjdk@21 (macOS), or https://adoptium.net"
major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "${major:-0}" -ge 17 ] || fail "Java 17 or newer is needed (this is Java ${major:-?})"

if [ "$version" = latest ]; then url="https://github.com/$repo/releases/latest/download/okay-remote.jar"
else url="https://github.com/$repo/releases/download/v$version/okay-remote.jar"; fi
mkdir -p "$bin" "$cmd_dir"
say "downloading $url"
curl -fL --progress-bar -o "$bin/okay-remote.jar.new" "$url" || fail "the download failed"
mv "$bin/okay-remote.jar.new" "$bin/okay-remote.jar"

# the command: the daemon, and `okay-remote service install|uninstall|status` (start with the user's login)
cat > "$cmd_dir/okay-remote" <<'LAUNCHER'
#!/bin/sh
# okay-remote's command (made by install.sh)
set -eu
jar="$HOME/.okay-remote/bin/okay-remote.jar"
label=io.github.okay-effects.okay-remote
plist="$HOME/Library/LaunchAgents/$label.plist"
unit="$HOME/.config/systemd/user/okay-remote.service"
service() {
  case "${1:-}" in
    install)
      shift
      if [ "$(uname)" = Darwin ]; then
        mkdir -p "$HOME/Library/LaunchAgents"
        [ $# -gt 0 ] || set -- --relay
        args=""; for a in "$@"; do args="$args<string>$a</string>"; done
        cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key><array><string>$(command -v java)</string><string>-jar</string><string>$jar</string>$args</array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$PATH</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/.okay-remote/daemon.log</string>
  <key>StandardErrorPath</key><string>$HOME/.okay-remote/daemon.log</string>
</dict></plist>
PLIST
        launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
        launchctl bootstrap "gui/$(id -u)" "$plist"
      else
        mkdir -p "$(dirname "$unit")"
        [ $# -gt 0 ] || set -- --relay
        printf '[Unit]\nDescription=okay-remote\n\n[Service]\nEnvironment=PATH=%s\nExecStart=%s -jar %s %s\nRestart=always\n\n[Install]\nWantedBy=default.target\n' \
          "$PATH" "$(command -v java)" "$jar" "$*" > "$unit"
        systemctl --user daemon-reload && systemctl --user enable --now okay-remote
      fi
      echo "okay-remote runs now, and with every login; its log: ~/.okay-remote/daemon.log" ;;
    uninstall)
      if [ "$(uname)" = Darwin ]; then launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true; rm -f "$plist"
      else systemctl --user disable --now okay-remote 2>/dev/null || true; rm -f "$unit"; fi
      echo "okay-remote no longer starts by itself" ;;
    status)
      if [ "$(uname)" = Darwin ]; then launchctl print "gui/$(id -u)/$label" 2>/dev/null | grep -E "state|pid" || echo "not installed"
      else systemctl --user status okay-remote --no-pager 2>/dev/null || echo "not installed"; fi
      grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$HOME/.okay-remote/daemon.log" 2>/dev/null | tail -1 ;;
    *) echo "okay-remote service install [options, --relay by default] | uninstall | status"; exit 1 ;;
  esac
}
if [ "${1:-}" = service ]; then shift; service "$@"; exit; fi
exec java -jar "$jar" "$@"
LAUNCHER
chmod +x "$cmd_dir/okay-remote"

say ""
say "okay-remote $(java -jar "$bin/okay-remote.jar" --version | sed 's/okay-remote //') installed: $cmd_dir/okay-remote"
case ":$PATH:" in *":$cmd_dir:"*) ;; *) say "add it to your PATH: echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.zshrc";; esac
command -v claude >/dev/null || say "note: no coding agent found yet — install Claude Code (https://claude.com/claude-code) or another"
say ""
say "start it:            okay-remote --relay"
say "or with every login: okay-remote service install"
