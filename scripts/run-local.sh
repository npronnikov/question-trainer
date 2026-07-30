#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
codex_path="$(command -v codex || true)"

if [[ -z "$codex_path" ]]; then
  echo "Codex CLI не найден в PATH" >&2
  exit 1
fi

export APP_DATA_DIR="$project_dir/backend/data"
export ACP_WORKSPACE="$project_dir"
export ACP_AGENT_COMMAND="${ACP_AGENT_COMMAND:-npx}"
export ACP_AGENT_ARGS="${ACP_AGENT_ARGS:--y,@agentclientprotocol/codex-acp}"
export CODEX_PATH="${CODEX_PATH:-$codex_path}"
export INITIAL_AGENT_MODE="${INITIAL_AGENT_MODE:-read-only}"
export SERVER_PORT="${SERVER_PORT:-8081}"
export BACKEND_URL="${BACKEND_URL:-http://localhost:$SERVER_PORT}"

backend_pid=""
frontend_pid=""

cleanup() {
  [[ -n "$frontend_pid" ]] && kill "$frontend_pid" 2>/dev/null || true
  [[ -n "$backend_pid" ]] && kill "$backend_pid" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

(
  cd "$project_dir/backend"
  mvn spring-boot:run
) &
backend_pid=$!

node "$project_dir/scripts/dev-server.mjs" &
frontend_pid=$!

echo "Локальный Codex: $CODEX_PATH"
echo "Backend: $BACKEND_URL"
echo "Приложение: http://localhost:${FRONTEND_PORT:-8090}"
echo "Для остановки нажмите Ctrl+C"

while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$backend_pid" 2>/dev/null; then
  wait "$backend_pid"
else
  wait "$frontend_pid"
fi
