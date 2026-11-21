#!/usr/bin/env bash
# Start the Caramel AI tech-support chat locally.
#
#   backend   Spring Boot (mvn spring-boot:run)  -> http://localhost:8082
#   frontend  Vite dev server (npm run dev)      -> http://localhost:5173
#
# The backend port is set in backend/src/main/resources/application.properties (server.port)
# and must match the /api proxy target in frontend/vite.config.ts. Keep BACKEND_PORT below in sync.
#
# Both servers start concurrently. The script waits for the frontend to answer,
# then opens it in Google Chrome. If either server exits, the other is stopped too.
# Ctrl+C stops both servers.
#
# Usage: ./run.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_PORT=8082
FRONTEND_PORT=5173
FRONTEND_URL="http://localhost:${FRONTEND_PORT}/"
FRONTEND_READY_TIMEOUT=120  # seconds

BACKEND_PID=""
FRONTEND_PID=""

log() { printf '[run.sh] %s\n' "$*"; }
die() { printf '[run.sh] ERROR: %s\n' "$*" >&2; exit 1; }

# --- Preflight checks -------------------------------------------------------------------
for tool in mvn npm curl; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but was not found on PATH."
done

# Fail fast if a port is taken. Otherwise the backend cannot bind, and the frontend's /api
# proxy reaches whatever else is listening, which shows up in the UI as a generic error.
check_port_free() {
  local port="$1" owner=""
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    if command -v lsof >/dev/null 2>&1; then
      # Best effort only: lsof can fail when the owner runs under another account.
      owner="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1 " (pid " $2 ")"}' || true)"
    fi
    die "port $port is already in use${owner:+ by $owner}. Stop that process, then run ./run.sh again."
  fi
}
check_port_free "$BACKEND_PORT"
check_port_free "$FRONTEND_PORT"

CHROME_LINUX_BIN=""
case "$(uname -s)" in
  Darwin)
    [[ -d "/Applications/Google Chrome.app" ]] || die "Google Chrome is not installed in /Applications."
    ;;
  Linux)
    for candidate in google-chrome google-chrome-stable; do
      if command -v "$candidate" >/dev/null 2>&1; then
        CHROME_LINUX_BIN="$candidate"
        break
      fi
    done
    [[ -n "$CHROME_LINUX_BIN" ]] || die "Google Chrome (google-chrome) was not found on PATH."
    ;;
  *)
    die "Unsupported OS: $(uname -s). Opening Chrome is only supported on Linux and macOS."
    ;;
esac

if [[ ! -f "$ROOT_DIR/.env" && -z "${GOOGLE_API_KEY:-}" ]]; then
  log "WARNING: no .env file and no GOOGLE_API_KEY in the environment."
  log "         The backend refuses to start without a key. Copy .env.example to .env and set it."
fi

if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  log "frontend/node_modules not found; running npm install..."
  (cd "$FRONTEND_DIR" && npm install)
fi

# --- Process management -------------------------------------------------------------
# Job control gives each background server its own process group, so cleanup can stop
# the whole tree (Maven/JVM and the Vite/Node child) with one signal.
set -m

cleanup() {
  trap - EXIT
  log "Stopping servers..."
  for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
    if [[ -n "$pid" ]]; then
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    fi
  done
  # Wait only for the two servers. A bare `wait` would also block on the Chrome launch below,
  # which stays open after this script exits.
  wait "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
  log "Stopped."
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# --- Start both servers concurrently ------------------------------------------------
log "Starting backend on http://localhost:${BACKEND_PORT} ..."
(cd "$BACKEND_DIR" && exec mvn spring-boot:run) \
  > >(sed -u 's/^/[backend]  /') 2>&1 &
BACKEND_PID=$!

log "Starting frontend on $FRONTEND_URL ..."
(cd "$FRONTEND_DIR" && exec npm run dev -- --port "$FRONTEND_PORT" --strictPort) \
  > >(sed -u 's/^/[frontend] /') 2>&1 &
FRONTEND_PID=$!

# --- Wait for the frontend to be ready ----------------------------------------------
log "Waiting for the frontend to be ready (timeout ${FRONTEND_READY_TIMEOUT}s)..."
elapsed=0
until curl -fsS -o /dev/null "$FRONTEND_URL" 2>/dev/null; do
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    die "The frontend exited before it became ready. See the [frontend] output above."
  fi
  if (( elapsed >= FRONTEND_READY_TIMEOUT )); then
    die "The frontend did not respond at $FRONTEND_URL within ${FRONTEND_READY_TIMEOUT}s."
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
log "Frontend is ready at $FRONTEND_URL"

# --- Open Chrome ----------------------------------------------------------------------
log "Opening $FRONTEND_URL in Google Chrome..."
if [[ "$(uname -s)" == "Darwin" ]]; then
  open -a "Google Chrome" "$FRONTEND_URL"
else
  # Run detached: when Chrome is not already running, this command blocks until Chrome exits.
  "$CHROME_LINUX_BIN" "$FRONTEND_URL" >/dev/null 2>&1 &
fi

log "Both servers are running. Press Ctrl+C to stop."

# --- Keep running until Ctrl+C or until either server exits --------------------------
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 2
done

if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  log "The backend exited. Shutting down the frontend."
else
  log "The frontend exited. Shutting down the backend."
fi
exit 1
