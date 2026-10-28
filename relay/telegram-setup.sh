#!/bin/bash
# THE RELAY'S TELEGRAM BOT (specs/remote.md, "Telegram"): its token typed here (never shown, never in a file), checked
# with Telegram, kept as the Worker's secrets with a webhook secret made here; the webhook pointed at the relay.
#   relay/telegram-setup.sh [https://<your relay>]      (Cloudflare's token from relay/.env, as deploy.sh)
# Make the bot first: Telegram → @BotFather → /newbot → its token.
set -euo pipefail
cd "$(dirname "$0")"
relay="${1:-https://okay-remote.okay-effects.workers.dev}"
[ -f .env ] || { echo "no relay/.env: CLOUDFLARE_API_TOKEN=… (a token with Workers edit)"; exit 1; }
set -a; . ./.env; set +a
[ -d node_modules ] || npm install --silent
read -r -s -p "The bot's token (from @BotFather; not shown): " token; echo
me=$(curl -fsS "https://api.telegram.org/bot$token/getMe") || { echo "Telegram does not know this token"; exit 1; }
bot=$(printf '%s' "$me" | sed -n 's/.*"username":"\([^"]*\)".*/\1/p')
[ -n "$bot" ] || { echo "no username in Telegram's answer"; exit 1; }
secret=$(openssl rand -hex 32)
printf '%s' "$token" | npx wrangler secret put TG_TOKEN >/dev/null
printf '%s' "$secret" | npx wrangler secret put TG_SECRET >/dev/null
printf '%s' "$bot" | npx wrangler secret put TG_BOT >/dev/null
curl -fsS "https://api.telegram.org/bot$token/setWebhook" -d "url=${relay%/}/tg" -d "secret_token=$secret" \
  -d 'allowed_updates=["message"]' -d "drop_pending_updates=true" >/dev/null
curl -fsS "https://api.telegram.org/bot$token/setMyDescription" \
  --data-urlencode "description=Tells you when okay-remote waits for you on your computer — only that, never what." \
  >/dev/null || true
unset token secret
echo "@$bot is the relay's bot: ${relay%/}/tg. Restart okay-remote, then on its page: Phone → Link Telegram."
