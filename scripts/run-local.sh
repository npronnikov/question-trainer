#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"

if (( $# > 1 )) || { (( $# == 1 )) && [[ $1 != "--check-config" ]]; }; then
  echo "Использование: $0 [--check-config]" >&2
  exit 2
fi

check_config=false
[[ ${1:-} == "--check-config" ]] && check_config=true

if [[ -f "$project_dir/.env" ]]; then
  exported_environment="$(export -p)"
  set -a
  # shellcheck disable=SC1091
  source "$project_dir/.env"
  set +a
  eval "$exported_environment"
fi

codex_works() {
  [[ -n "$1" && -x "$1" ]] && "$1" --version >/dev/null 2>&1
}

if [[ -n ${CODEX_PATH:-} ]]; then
  if ! codex_works "$CODEX_PATH"; then
    echo "Настроенный CODEX_PATH не запускается: $CODEX_PATH" >&2
    exit 1
  fi
else
  path_codex="$(command -v codex || true)"
  chatgpt_codex="/Applications/ChatGPT.app/Contents/Resources/codex"
  for candidate in "$path_codex" "$chatgpt_codex"; do
    if codex_works "$candidate"; then
      CODEX_PATH="$candidate"
      break
    fi
  done
  if [[ -z ${CODEX_PATH:-} ]]; then
    echo "Рабочий Codex CLI не найден. Задайте CODEX_PATH в $project_dir/.env" >&2
    exit 1
  fi
fi

export APP_DATA_DIR="$project_dir/backend/data"
export ACP_WORKSPACE="$project_dir"
export ACP_AGENT_COMMAND="${ACP_AGENT_COMMAND:-npx}"
export ACP_AGENT_ARGS="${ACP_AGENT_ARGS:--y,@agentclientprotocol/codex-acp}"
export CODEX_PATH
export INITIAL_AGENT_MODE="${INITIAL_AGENT_MODE:-read-only}"
export SERVER_PORT="${SERVER_PORT:-8081}"
export BACKEND_URL="${BACKEND_URL:-http://localhost:$SERVER_PORT}"

echo "Локальный Codex: $CODEX_PATH"
if [[ "$check_config" == true ]]; then
  exit 0
fi

backend_pid=""
frontend_pid=""
cleaned_up=false

cleanup() {
  if [[ "$cleaned_up" == true ]]; then
    return
  fi
  cleaned_up=true

  echo
  echo "Останавливаем frontend и backend…"
  [[ -n "$frontend_pid" ]] && kill "$frontend_pid" 2>/dev/null || true
  [[ -n "$backend_pid" ]] && kill "$backend_pid" 2>/dev/null || true
  [[ -n "$frontend_pid" ]] && wait "$frontend_pid" 2>/dev/null || true
  [[ -n "$backend_pid" ]] && wait "$backend_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

(
  cd "$project_dir/backend"
  exec mvn spring-boot:run
) &
backend_pid=$!

node "$project_dir/scripts/dev-server.mjs" &
frontend_pid=$!

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
