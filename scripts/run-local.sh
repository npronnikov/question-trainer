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
export FRONTEND_PORT="${FRONTEND_PORT:-8090}"
export BACKEND_URL="${BACKEND_URL:-http://localhost:$SERVER_PORT}"

validate_port() {
  local name="$1"
  local value="$2"

  if [[ ! "$value" =~ ^[1-9][0-9]{0,4}$ ]] || (( value > 65535 )); then
    echo "$name должен быть целым числом от 1 до 65535: $value" >&2
    exit 1
  fi
}

validate_port SERVER_PORT "$SERVER_PORT"
validate_port FRONTEND_PORT "$FRONTEND_PORT"

if [[ "$SERVER_PORT" == "$FRONTEND_PORT" ]]; then
  echo "SERVER_PORT и FRONTEND_PORT должны отличаться: $SERVER_PORT" >&2
  exit 1
fi

echo "Локальный Codex: $CODEX_PATH"
if [[ "$check_config" == true ]]; then
  exit 0
fi

if ! command -v lsof >/dev/null 2>&1; then
  echo "Для освобождения портов требуется утилита lsof" >&2
  exit 1
fi

listeners_on_port() {
  lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null || true
}

signal_listeners() {
  local signal="$1"
  local pids="$2"
  local pid

  for pid in $pids; do
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      kill -"$signal" "$pid" 2>/dev/null || true
    fi
  done
}

stop_listeners_on_port() {
  local port="$1"
  local pids
  local remaining
  local attempt

  pids="$(listeners_on_port "$port")"
  if [[ -z "$pids" ]]; then
    return
  fi

  echo "Освобождаем порт $port (PID: ${pids//$'\n'/, })…"
  signal_listeners TERM "$pids"

  for (( attempt = 0; attempt < 50; attempt++ )); do
    remaining="$(listeners_on_port "$port")"
    if [[ -z "$remaining" ]]; then
      return
    fi
    sleep 0.1
  done

  echo "Процессы на порту $port не завершились по TERM, отправляем KILL…"
  signal_listeners KILL "$remaining"

  for (( attempt = 0; attempt < 20; attempt++ )); do
    remaining="$(listeners_on_port "$port")"
    if [[ -z "$remaining" ]]; then
      return
    fi
    sleep 0.1
  done

  echo "Не удалось освободить порт $port (PID: ${remaining//$'\n'/, })" >&2
  return 1
}

stop_listeners_on_port "$SERVER_PORT"
stop_listeners_on_port "$FRONTEND_PORT"

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
echo "Приложение: http://localhost:$FRONTEND_PORT"
echo "Для остановки нажмите Ctrl+C"

while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$backend_pid" 2>/dev/null; then
  wait "$backend_pid"
else
  wait "$frontend_pid"
fi
