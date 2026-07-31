# «Вопросы-взломщики» — тренажёр + ACP-коуч

Монорепозиторий веб-приложения из трёх частей:

1. **Теория** — семь базовых категорий, формулы, примеры и типичные ошибки.
2. **Офлайн-тренажёр** — 56 ситуаций, четыре варианта ответа, переворот карточки, очки и серия правильных ответов. Прогресс хранится в `localStorage`; после первого открытия теория и тренажёр работают без сети.
3. **Обучение в чате** — длинные Markdown-ответы, история диалогов в H2 file и потоковая выдача через SSE. Backend запускает ACP-совместимого coding agent как дочерний процесс; когда агент не настроен, включается содержательный локальный fallback-коуч.

## Быстрый запуск

```bash
cp .env.example .env
# Заполните `CODEX_API_KEY` или `OPENAI_API_KEY` для дефолтного агента
docker compose up --build
```

Откройте `http://localhost:8088`.

## Локальный запуск с авторизованным Codex

Требования: Java 21, Maven 3.9+, Node.js/npm и установленный, авторизованный
Codex CLI (`codex login`).

```bash
./scripts/run-local.sh
```

Откройте `http://localhost:8090`. Скрипт запускает Spring Boot на порту 8081,
локальный frontend-сервер на 8090 и `@agentclientprotocol/codex-acp` через
`npx`. Переменная `CODEX_PATH` указывает адаптеру на установленный в системе
`codex`, поэтому используется его локальная авторизация из `~/.codex`.

По умолчанию агент работает в режиме `read-only`. Команду ACP, frontend-порт
и модель можно переопределить:

```bash
FRONTEND_PORT=8091 \
SERVER_PORT=8082 \
CODEX_CONFIG='{"model":"gpt-5.6-terra"}' \
./scripts/run-local.sh
```

Остановить оба процесса можно через `Ctrl+C`.

Без Docker фронтенд можно открыть отдельно:

```bash
cd frontend
python3 -m http.server 8090
```

Теория и карточки будут работать; чат покажет, что backend недоступен.

## Backend локально

Требования: Java 21, Maven 3.9+, Node.js/npm — только если используется дефолтный `npx @agentclientprotocol/codex-acp`.

```bash
cd backend
mvn spring-boot:run
```

H2 создаёт файл `backend/data/question-hacker.mv.db`. Консоль H2: `http://localhost:8080/h2-console`, JDBC URL: `jdbc:h2:file:./data/question-hacker`.

## ACP

Конфигурация находится в `backend/src/main/resources/application.yml` и переопределяется env-переменными:

- `ACP_ENABLED`
- `ACP_AGENT_COMMAND`
- `ACP_AGENT_ARGS` — CSV-список аргументов
- `ACP_WORKSPACE`
- `ACP_TIMEOUT`
- `ACP_MODELS` — CSV-список моделей, доступных в селекторе режима `#coach`
- `ACP_DEFAULT_MODEL` — модель, выбранная по умолчанию
- `CODEX_API_KEY` или `OPENAI_API_KEY` для `codex-acp`

Приложение рекламирует агенту только чтение/запись текста в пределах `ACP_WORKSPACE`. Доступ за пределы workspace блокируется, размер читаемого/записываемого файла ограничен 512 КиБ. Терминал и автоматическое подтверждение опасных операций не включены. Для дефолтного адаптера также задаются `NO_BROWSER=1` и `INITIAL_AGENT_MODE=read-only`.

## Отдельные промпты

- `backend/src/main/resources/prompts/training-coach.md`
- `backend/src/main/resources/prompts/scenario-generator.md`

Промпты загружаются как ресурсы и не зашиты в Java-код.

## API

- `GET /api/system/status`
- `GET /api/chat/sessions`
- `POST /api/chat/sessions`
- `GET /api/chat/sessions/{id}/messages`
- `POST /api/chat/sessions/{id}/messages`
- `GET /api/chat/runs/{runId}/events` — SSE
- `GET /api/scenarios/generated`
- `POST /api/scenarios/generate`
- `POST /api/practice/scenario` — новая ситуация для управляемой практики
- `POST /api/practice/review` — проверка вопроса и идеи пользователя

## Структура данных

`chat_session` — диалоги; `chat_message` — сообщения и источник (`USER`, `ACP`, `FALLBACK`); `generated_scenario` — дополнительные ситуации, созданные агентом.

## Проверки

```bash
cd backend && mvn test
node --check frontend/app.js
```
