# «Вопросы-взломщики» — тренажёр + ACP-коуч

Монорепозиторий серверного веб-приложения из пяти частей:

1. **Теория** — семь серверных категорий, формулы, примеры, контрасты и доказательные источники.
2. **Адаптивный тренажёр** — 98 проверенных карточек трёх уровней; backend выдаёт варианты, проверяет ответ и обновляет освоение.
3. **Практика полного цикла** — персональная последовательность модерированных ситуаций: вопрос, ответ, рассуждение и решение с серверной оценкой полноты, попадания в категорию и силы вопроса.
4. **Обучение в чате** — персональная история и потоковая выдача через SSE; при отключённом агенте работает методический fallback.
5. **Контур модерации** — сгенерированные ситуации проходят автоматическую отбраковку и ручное решение администратора до публикации.

Аккаунты, права доступа, учебная программа, правильные ответы, оценки, прогресс и публикация кейсов контролируются backend. Frontend обслуживает формы и отображает серверные ответы; `/api/**`, кроме явно публичных auth/status-маршрутов, требует серверную сессию.

## Быстрый запуск

```bash
cp .env.example .env
# Задайте уникальный APP_ADMIN_PASSWORD длиной не менее 12 символов.
# Заполните CODEX_API_KEY или OPENAI_API_KEY для дефолтного агента.
docker compose up --build
```

Откройте `http://localhost:8088`.

При первом запуске backend создаёт администратора из `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` и необязательного `APP_ADMIN_EMAIL`. Если администратор уже существует, его пароль и роли автоматически не меняются. Чтобы не создавать администратора, оставьте и username, и password пустыми; частичная конфигурация считается ошибкой запуска.

Для production включите TLS на reverse proxy и задайте `SESSION_COOKIE_SECURE=true`. Не используйте пример пароля или общий пароль между окружениями.

## Локальный запуск с авторизованным Codex

Требования: Java 21, Maven 3.9+, Node.js/npm и установленный, авторизованный
Codex CLI (`codex login`).

```bash
./scripts/run-local.sh
```

Откройте `http://localhost:8090`. Скрипт запускает Spring Boot на порту 8081,
локальный frontend-сервер на 8090 и `@agentclientprotocol/codex-acp` через
`npx`. Переменная `CODEX_PATH` указывает адаптеру на установленный в системе
`codex`, поэтому используется его локальная авторизация из `~/.codex`. Локальный
скрипт загружает корневой `.env`, проверяет `CODEX_PATH` через `--version` и
останавливается до запуска сервисов, если бинарник сломан. Явно переданные
переменные окружения имеют приоритет над `.env`.

По умолчанию агент работает в режиме `read-only`. Команду ACP, frontend-порт
и модель можно переопределить:

```bash
FRONTEND_PORT=8091 \
SERVER_PORT=8082 \
CODEX_CONFIG='{"model":"gpt-5.6-terra"}' \
./scripts/run-local.sh
```

Остановить оба процесса можно через `Ctrl+C`.

Не открывайте `frontend/index.html` напрямую и не запускайте его отдельным статическим сервером: session cookie и CSRF намеренно работают через единый origin frontend-прокси и backend.

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
- `backend/src/main/resources/prompts/scenario-candidates-cycled-v1.md`
- `backend/src/main/resources/prompts/practice-assessment-v2.md` — активная трёхпольная оценка
- `backend/src/main/resources/prompts/practice-assessment-v1.md` — legacy-схема для чтения истории

Промпты загружаются как ресурсы и не зашиты в Java-код.

Генерация ситуаций доступна только администратору в разделе модерации. Backend передаёт ACP точную последовательность категорий `1…7`, затем снова `1`, сохраняет каждый ответ как кандидат и не допускает его в «Практику» до ручной публикации.

## API

- `GET /api/system/status`
- `GET /api/auth/csrf` — выдаёт имя CSRF-заголовка и токен
- `POST /api/auth/register` — создаёт локальный аккаунт и сессию
- `POST /api/auth/login` — открывает сессию по username или email
- `POST /api/auth/logout` — завершает текущую сессию
- `GET /api/auth/me` — возвращает текущего пользователя и роли
- `GET /api/chat/sessions`
- `POST /api/chat/sessions`
- `DELETE /api/chat/sessions/{id}`
- `GET /api/chat/sessions/{id}/messages`
- `POST /api/chat/sessions/{id}/messages`
- `GET /api/chat/runs/{runId}/events` — SSE
- `GET /api/curriculum/categories` и `GET /api/curriculum/categories/{code}`
- `GET /api/trainer/next` и `POST /api/trainer/attempts`
- `GET /api/progress`
- `POST /api/practice/assignments` — выдаёт следующую опубликованную ситуацию без вызова ACP; категория определяется сервером
- `GET /api/practice/cycles` и `GET /api/practice/cycles/{assignmentId}` — персональная история и полный timeline цикла
- `PUT /api/practice/cycles/{assignmentId}/draft` — серверное автосохранение черновика
- `GET /api/practice/examples/random` — случайный опубликованный пример полного цикла
- `POST /api/practice/attempts`, `GET /api/practice/attempts/{id}`, `POST /api/practice/attempts/{id}/revisions` и `POST /api/practice/attempts/{id}/retries`
- `GET /api/practice/attempts/{id}/events` — SSE статуса оценки
- `/api/admin/scenario-candidates/**` — ACP-генерация, просмотр, правка, отказ и публикация (только ADMIN)

У каждого пользователя свой цикл категорий `1…7 → 1…7`; уже пройденный этим пользователем сценарий не повторяется. Незавершённые циклы остаются в истории и не мешают запросить новую ситуацию. Встроенные сценарии программы используются тренажёром, но не входят в каталог «Практики». Если для следующей категории нет нового опубликованного кандидата, API возвращает `PRACTICE_CATALOG_EXHAUSTED`, и интерфейс просит дождаться публикации администратора.

Попытка практики состоит из трёх полей: `question`, `rationale`, `solution`. Сервер отдельно проверяет вопрос и решение, соответствие вопроса целевой категории и силу вопроса. Обоснование служит проверкой связности: `WEAK` даёт рекомендацию, но не блокирует зачёт; только `CONTRADICTS` требует исправления. Поля для исправления вычисляет backend, поэтому модель не может самостоятельно поставить зачёт или расширить область редактирования. При низкой уверенности модели попытка получает `UNVERIFIED` без семантических баллов и может быть отправлена повторно.

## Структура данных

`chat_session` и `chat_message` хранят персональные диалоги. `category`, `theory_section`, `scenario` и `scenario_option` образуют серверную программу. `trainer_issuance`, `trainer_attempt`, `category_mastery` и `category_confusion` хранят персональную траекторию. `practice_*` хранит полный цикл практики и аудируемую оценку. `scenario_candidate` и `moderation_action` образуют очередь качества; только связь `scenario_candidate.status=PUBLISHED → scenario` включает ситуацию в каталог практики.

`app_user` и `user_role` хранят локальные аккаунты и роли. Пароли сохраняются только как BCrypt hash. Каждый `chat_session` принадлежит конкретному пользователю; чужой идентификатор возвращает `404`, не раскрывая существование ресурса.

## Проверки

```bash
cd backend && mvn test
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check scripts/dev-server.mjs
docker compose config
```
