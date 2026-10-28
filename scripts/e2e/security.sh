#!/bin/bash
# THE DAEMON AS A HOSTILE PAGE OR PROCESS WOULD TRY IT: every answer must be as written. `sbt fatJar` first.
set -u
home=/tmp/okay-remote-security-home; rm -rf "$home"; mkdir -p "$home/.okay-remote"
java -Duser.home="$home" -jar "$(dirname "$0")/../../dist/okay-remote.jar" --port 7793 2>/dev/null &
daemon=$!; trap 'kill $daemon' EXIT; sleep 3
t=$(cat "$home/.okay-remote/token"); u=http://127.0.0.1:7793; fails=0
expect() { # what, wanted status, curl args…
  local what=$1 want=$2; shift 2
  local got; got=$(curl -s -o /dev/null -w "%{http_code}" "$@")
  if [ "$got" = "$want" ]; then echo "✓ $what ($got)"; else echo "✗ $what: $got, not $want"; fails=$((fails+1)); fi
}
expect "the page, no token needed" 200 "$u/"
ok() { echo "✓ $1"; }; no() { echo "✗ $1"; fails=$((fails+1)); }
if [ ${#t} -ge 48 ]; then ok "the token: 24 random bytes"; else no "the token is short"; fi
expect "events without the token" 401 "$u/events"
expect "events with a wrong token" 401 "$u/events?t=${t%?}0"
expect "an act without the token" 401 -X POST -d '{"type":"hello"}' "$u/act"
expect "an act with the token" 200 -X POST -d '{"type":"hello"}' "$u/act?t=$t"
expect "an act from another site's page" 403 -X POST -H "Origin: https://evil.example" -d '{"type":"hello"}' \
  "$u/act?t=$t"
expect "a renamed host (DNS rebinding)" 421 -H "Host: evil.example:7793" "$u/act?t=$t"
expect "a renamed host, even for the page" 421 -H "Host: evil.example:7793" "$u/"
expect "not JSON" 400 -X POST -d 'nope' "$u/act?t=$t"
expect "the hook of no session" 403 -X POST -d '{"tool_name":"Bash"}' "$u/hook/x?k=y"
expect "the hook, not JSON" 400 -X POST -d 'nope' "$u/hook/x?k=y"
expect "a place that is not" 404 "$u/etc/passwd?t=$t"
expect "a module name that is not one: not served" 401 --path-as-is "$u/internal-../../x.js"
expect "a body over 4 MB" 413 -X POST --data-binary @<(head -c 4100000 /dev/zero | tr '\0' a) "$u/act?t=$t"
h=$(curl -s -D - -o /dev/null "$u/")
for header in "content-security-policy: default-src 'self'" "x-content-type-options: nosniff" \
  "referrer-policy: no-referrer"; do
  if echo "$h" | grep -qi "$header"; then echo "✓ $header"; else echo "✗ no $header"; fails=$((fails+1)); fi
done
p=$(stat -f %Lp "$home/.okay-remote/token")
if [ "$p" = 600 ]; then ok "the token's file is its owner's alone (600)"; else no "the token's file: $p"; fi
exit $fails
