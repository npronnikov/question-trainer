# Архитектура

```text
Browser / PWA — тонкий клиент
  ├─ теория и тренажёр ← REST
  ├─ персональная практика ← REST + SSE
  ├─ коуч ← REST + SSE
  └─ модерация (только ADMIN) ← REST
                 │
                 ▼
Spring Boot 3 / Java 21
  ├─ curriculum + trainer services
  ├─ practice assignment / cycle / assessment services
  ├─ moderation service
  ├─ PromptCatalog (отдельные *.md)
  ├─ JdbcTemplate ── H2 file
  └─ AcpGateway
        ├─ stdio JSON-RPC 2.0
        ├─ session/new + session/prompt
        ├─ agent_message_chunk → SSE для коуча
        └─ bounded workspace file bridge
            │
            ▼
  npx @agentclientprotocol/codex-acp
            │
            ▼
        Codex coding agent
```

Frontend не хранит программу, правильные ответы или прогресс в `localStorage`. Сервер является источником истины для учебного контента, персональной истории, прав доступа, порядка выдачи и оценок.

## Поток практики и модерации

```text
ADMIN: «В очередь»
  → backend блокирует позицию глобальной очереди
  → вычисляет категории 1…7 → 1…7
  → ACP генерирует объекты строго в этом порядке
  → scenario_candidate
  → автоматическая проверка
  → ручная модерация
  → PUBLISHED → scenario
                        │
                        ▼
USER: «Новая ситуация»
  → backend блокирует строку пользователя
  → запрещает выдачу, если существует цикл со статусом не PASSED
  → вычисляет личную категорию по числу assignment
  → выбирает непройденный scenario только из PUBLISHED-кандидатов
  → practice_assignment + server draft
  → попытка и ACP-оценка
  → PASSED открывает следующую категорию
```

Глобальная последовательность административной генерации и персональная последовательность пользователя независимы. В глобальном счётчике учитываются все сохранённые кандидаты, включая отклонённые. В личном счётчике учитываются сохранённые `practice_assignment`; один сценарий можно выдать разным пользователям, но нельзя повторно выдать одному пользователю.

Встроенная таблица `scenario` остаётся основой тренажёра распознавания категорий. Для «Практики» запись обязана быть связана с `scenario_candidate` в статусе `PUBLISHED`, поэтому начальный каталог практики пуст. Если для очередной категории нет непройденной публикации, backend возвращает `PRACTICE_CATALOG_EXHAUSTED`; Practice не вызывает ACP автоматически.

## Поток сообщения коучу

1. Frontend сохраняет пользовательское сообщение через `POST /api/chat/sessions/{id}/messages`.
2. Backend создаёт `runId` и сразу отвечает браузеру.
3. Browser открывает `EventSource` на `/api/chat/runs/{runId}/events`.
4. `AcpGateway` запускает agent как stdio-процесс, выполняет `initialize`, при наличии API-ключа — `authenticate("api-key")`, затем `session/new` и `session/prompt`.
5. Только `agent_message_chunk` превращаются в SSE-события `delta`. Внутренние thought/reasoning chunks в UI не передаются.
6. Итоговый Markdown сохраняется в `chat_message`; SSE завершается событием `done`.

При ошибке до начала стрима backend выдаёт локальный методический fallback, чтобы UX коуча не обрывался. Недоступность ACP не мешает пользователю получать уже опубликованные ситуации практики.

## Границы безопасности

- Административная генерация защищена ролью `ADMIN`; пользовательские Practice-endpoints ACP не вызывают.
- Агенту не рекламируется terminal capability.
- Файловые операции разрешены только внутри `ACP_WORKSPACE`.
- Нормализованный путь обязан начинаться с workspace root.
- Чтение/запись ограничены 512 КиБ на файл.
- По умолчанию Codex ACP запускается с `INITIAL_AGENT_MODE=read-only` и `NO_BROWSER=1`.
- API-ключи передаются только в окружение дочернего агента и не сохраняются в H2.
- Создание assignment сериализуется блокировкой пользователя, а глобальная позиция генерации — блокировкой первой категории.

## Замена агента

Любой stdio ACP agent можно подключить без изменений frontend:

```env
ACP_AGENT_COMMAND=/opt/my-agent
ACP_AGENT_ARGS=--acp,--profile,coach
```

Если агент использует другой способ authentication, адаптируйте блок в `AcpGateway`.
