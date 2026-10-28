#!/bin/bash
# the relay deployed: built (sbt relayDist), then `wrangler deploy` with the token from relay/.env (never committed)
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "no relay/.env: CLOUDFLARE_API_TOKEN=… (a token with Workers edit)"; exit 1; }
set -a; . ./.env; set +a
(cd .. && sbt -J-Xmx4g relayDist)
[ -d node_modules ] || npm install
npx wrangler deploy
