#!/usr/bin/env bash
# Starts the real Worker locally (workerd + local D1) with freshly generated throwaway keys.
# Used by the end-to-end tests and CI. Writes:
#   .dev.vars            throwaway secrets (git-ignored)
#   .e2e/env             LICENSE_URL, ADMIN_TOKEN, LICENSE_PUBLIC_KEY for the test scripts
# Usage: scripts/local-server.sh start|stop
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PORT:-8787}"
case "${1:-start}" in
  start)
    mkdir -p .e2e
    node scripts/generate-keys.mjs --dev-vars > .dev.vars
    PUB=$(grep '^# PUBLIC_KEY=' .dev.vars | cut -d= -f2-)
    ADMIN=$(grep '^ADMIN_TOKEN=' .dev.vars | cut -d'"' -f2)
    rm -rf .wrangler/state
    npx wrangler d1 migrations apply DB --local >/dev/null
    export WRANGLER_SEND_METRICS=false
    nohup npx wrangler dev --local --ip 0.0.0.0 --port "$PORT" > .e2e/server.log 2>&1 &
    echo $! > .e2e/server.pid
    for _ in $(seq 1 60); do
      if curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then break; fi
      sleep 1
    done
    curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null
    printf 'LICENSE_URL=http://127.0.0.1:%s\nADMIN_TOKEN=%s\nLICENSE_PUBLIC_KEY=%s\n' "$PORT" "$ADMIN" "$PUB" > .e2e/env
    echo "License server running on port $PORT"
    ;;
  stop)
    if [ -f .e2e/server.pid ]; then kill "$(cat .e2e/server.pid)" 2>/dev/null || true; rm -f .e2e/server.pid; fi
    pkill -f "wrangler dev --local" 2>/dev/null || true
    ;;
esac
