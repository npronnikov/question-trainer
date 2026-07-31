# Архитектура

```text
Browser / PWA
  ├─ theory.js + scenarios.js  ───────────────┐
  ├─ localStorage: score / streak / progress  │ offline
  └─ REST + SSE                               │
            │                                 │
            ▼                                 │
Spring Boot 3 / Java 21                       │
  ├─ ChatService + ScenarioService            │
  ├─ PromptCatalog (отдельные *.md)           │
  ├─ JdbcTemplate ── H2 file                  │
  └─ AcpGateway                               │
        ├─ stdio JSON-RPC 2.0                  │
        ├─ session/new + session/prompt        │
        ├─ agent_message_chunk → SSE           │
        └─ bounded workspace file bridge       │
            │
            ▼
  npx @agentclientprotocol/codex-acp
            │
            ▼
        Codex coding agent
```

## Почему два режима

- **Офлайн-ядро** не зависит от backend: теория, 56 карточек, переворот, очки, серия и прогресс.
- **Онлайн-обучение** хранит историю в H2 и вызывает ACP-агента. При ошибке до начала стрима backend выдаёт локальный методический fallback, чтобы UX не обрывался.

## Поток сообщения

1. Frontend сохраняет пользовательское сообщение через `POST /api/chat/sessions/{id}/messages`.
2. Backend создаёт `runId` и сразу отвечает браузеру.
3. Browser открывает `EventSource` на `/api/chat/runs/{runId}/events`.
4. `AcpGateway` запускает агент как stdio-процесс, выполняет `initialize`, при наличии API-ключа — `authenticate("api-key")`, затем `session/new` и `session/prompt`.
5. Только `agent_message_chunk` превращаются в SSE-события `delta`. Внутренние thought/reasoning chunks в UI не передаются.
6. Итоговый Markdown сохраняется в `chat_message`; SSE завершается событием `done`.

## Границы безопасности

- Агенту не рекламируется terminal capability.
- Файловые операции разрешены только внутри `ACP_WORKSPACE`.
- Нормализованный путь обязан начинаться с workspace root.
- Чтение/запись ограничены 512 КиБ на файл.
- По умолчанию Codex ACP запускается с `INITIAL_AGENT_MODE=read-only` и `NO_BROWSER=1`.
- API-ключи передаются только в окружение дочернего агента и не сохраняются в H2.

## Замена агента

Любой stdio ACP agent можно подключить без изменений frontend:

```env
ACP_AGENT_COMMAND=/opt/my-agent
ACP_AGENT_ARGS=--acp,--profile,coach
```

Если агент использует другой способ authentication, адаптируйте небольшой блок в `AcpGateway`.
