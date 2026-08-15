# План реализации

## Краткое резюме

Перевести режим «Практика» с четырёх полей `question → answer → reasoning → solution` на три канонических поля `question → rationale → solution`, сохранив существующие циклы, попытки, черновики и примеры. План состоит из семи шагов: совместимая миграция данных, новый контракт оценки, переход backend и frontend, удаление legacy-колонок, обновление тестов и итоговая проверка. Основной риск — корректно совместить старые `answer/reasoning` и не сломать область редактирования для существующих `NEEDS_REVISION` и `UNVERIFIED`-цепочек.

## Архитектурные решения

- **Три канонических поля**: публичный API, доменная модель, хранилище и UI используют только `question`, `rationale`, `solution`. `rationale` отображается как «Обоснование» и заменяет отдельные «Ответ» и «Рассуждение».
- **Сохранение истории**: старые значения объединяются без потерь в формате `Ответ на вопрос:\n{answer}\n\nХод рассуждения:\n{reasoning}`. Старые оценки `practice-assessment-v1` остаются аудиторными записями, но при чтении нормализуются в трёхполевое представление.
- **Версионированная оценка**: добавить `practice-assessment-v2` и prompt version `2`; существующий v1-ресурс и строки `prompt_version` не переписывать.
- **Обоснование — диагностический шаг**: состояние `WEAK` не мешает зачёту и попадает только в рекомендацию; `CONTRADICTS` блокирует зачёт, потому что показывает логический разрыв между вопросом и решением.
- **Сервер вычисляет поля для исправления**: убрать `fieldsToRevise` из ответа модели v2. Backend детерминированно назначает `question`, `rationale` и/или `solution` по проваленным критериям и сохраняет вычисленный список в существующем `fields_to_revise_json`.
- **Порог зачёта**: вопрос относится к целевой категории (`categoryFit >= 2`), имеет силу не ниже `3/4`, вопрос и решение проходят свои проверки, обоснование не противоречит цепочке, confidence — `MEDIUM` или `HIGH`. `LOW` не превращается в произвольную доработку: результат считается `UNVERIFIED` и использует существующий retry-поток.
- **Совместимый rollout для H2/Flyway**: сначала V11 добавляет и заполняет `rationale_text`, сохраняя legacy-колонки; после перехода кода V12 делает `rationale_text NOT NULL` и удаляет `answer_text/reasoning_text`.
- **Ломающий API допустим внутри монолита**: frontend и backend разворачиваются вместе; временный приём старого четырёхполевого JSON не добавляется.

---

## Шаги реализации

### Шаг 1 — Добавить совместимую колонку и миграционный тест (сложность: high)

**Цель**: подготовить БД к новому полю без потери пользовательской истории и без немедленной поломки текущего четырёхполевого кода.

**Файлы**:

- `backend/src/main/resources/db/migration/V11__add_three_field_practice_rationale.sql` — добавить nullable `rationale_text` в `practice_attempt`, `practice_draft`, `practice_example`; заполнить его объединёнными `answer_text` и `reasoning_text`; снять `NOT NULL` с двух legacy-колонок, чтобы новый код мог перестать их записывать.
- `backend/src/test/java/ru/questionhacker/trainer/ThreeFieldPracticeMigrationTest.java` — поднять H2 на Flyway target `10`, вставить попытку, черновик, пример и старую assessment-запись, затем мигрировать до V11 и проверить точное сохранение текста, parent-chain и статусов.
- `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java` — проверить наличие `RATIONALE_TEXT` во всех трёх таблицах.

**Детали реализации**:

```sql
ALTER TABLE practice_attempt ADD COLUMN rationale_text CLOB;
UPDATE practice_attempt
SET rationale_text = 'Ответ на вопрос:' || CHAR(10) || answer_text
  || CHAR(10) || CHAR(10) || 'Ход рассуждения:' || CHAR(10) || reasoning_text;

-- Аналогично для practice_draft и practice_example.
-- На этом шаге legacy-колонки ещё не удаляются.
```

Миграционный тест должен использовать многострочный Unicode-текст и отдельно проверить пустой новый черновик, чтобы объединение не давало `NULL`.

**Зависит от**: нет зависимостей.

**Проверка**:

- `cd backend && mvn -Dtest=ThreeFieldPracticeMigrationTest,MigrationTest test`
- Текущие backend-тесты продолжают работать поверх V11 до изменения Java-кода.

---

### Шаг 2 — Ввести параллельный контракт оценки `practice-assessment-v2` (сложность: medium)

**Цель**: формально закрепить новую семантику зачёта до переключения API и хранилища, не ломая активный v1-service на промежуточном шаге.

**Файлы**:

- `backend/src/main/resources/prompts/practice-assessment-v2.md` — новый prompt только для `question`, `rationale`, `solution`; явно запретить оценивать объективную ценность идеи и отделить слабое обоснование от противоречащего.
- `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV2.java` — добавить трёхшаговую chain-модель рядом с существующим `ModelAssessment`, который пока остаётся v1-контрактом.
- `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV2Parser.java` — валидировать schema v2, три уникальных поля, специальные статусы обоснования и прежние диапазоны category/strength.
- `backend/src/test/java/ru/questionhacker/trainer/practice/ModelAssessmentV2ParserTest.java` — добавить v2 fixtures и негативные проверки неизвестных полей/статусов.

**Детали реализации**:

```json
{
  "schemaVersion": "practice-assessment-v2",
  "chain": {
    "steps": [
      {"field":"question", "status":"PASS|FAIL", "evidence":"..."},
      {"field":"rationale", "status":"SUPPORTS|WEAK|CONTRADICTS", "evidence":"..."},
      {"field":"solution", "status":"PASS|FAIL", "evidence":"..."}
    ]
  },
  "categoryFit": {"score":0, "evidence":"...", "confusedWith":null},
  "questionStrength": {"score":0, "dimensions":[]},
  "confidence": "HIGH|MEDIUM|LOW",
  "strengths": [],
  "priorityCorrection": {"what":"...", "why":"...", "example":"..."},
  "feedback": "..."
}
```

`fieldsToRevise` и `verdict` отсутствуют: модель сообщает наблюдения, решение принимает backend. Parser по-прежнему самостоятельно пересчитывает `questionStrength.score` из четырёх dimensions.

**Зависит от**: шаг 1.

**Проверка**:

- `cd backend && mvn -Dtest=ModelAssessmentParserTest,ModelAssessmentV2ParserTest test`
- Parser принимает только один чистый JSON v2, отвергает Markdown, четвёртое поле и модельный `fieldsToRevise`.
- Существующие v1-tests и активный assessment service продолжают компилировать и работать до шага 3.

---

### Шаг 3 — Перевести backend, API и retry/revision на три поля (сложность: high)

**Цель**: сделать `rationale` единственным доменным полем вместо `answer/reasoning` и централизовать вычисление статуса и области исправления.

**Файлы**:

- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java` — читать/писать `rationale_text`; обновить `DraftRow`, `ExampleRow`, `AttemptRow`, `createEmptyDraft`, `saveDraft`, `createAttempt`, query mapper; включить `assessment.schema_version` в AttemptRow для v1-нормализации.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticePromptCatalog.java` — переключить активный prompt на v2 и зарегистрировать `PROMPT_VERSION = 2`, не удаляя v1.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentGateway.java` — заменить `answer/reasoning` одним `rationale` во входе модели.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java` — заменить сигнатуры Submission/Revision/Retry/AttemptView; валидировать три поля; вычислять verdict и `fieldsToRevise`; сохранить поведение idempotency, immutable history и retry scope.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java` — `ALL_FIELDS = [question, rationale, solution]`; обновить draft/editor/example DTO и серверную проверку заблокированных полей.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java` — заменить поля в AttemptRequest, RevisionRequest, RetryRequest, DraftRequest.
- `backend/src/main/java/ru/questionhacker/trainer/practice/AcpPracticeAssessmentGateway.java` — передавать prompt v2 без legacy-полей.
- `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumImporter.java` — импортировать `rationale` в `practice_example.rationale_text`.
- `backend/src/main/resources/curriculum/practice-examples.json` — объединить `answer` и `reasoning` в один содержательно отредактированный `rationale` для всех семи примеров, а не просто склеить строки.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java` — покрыть новые правила зачёта, ревизий и retry.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java` — проверить трёхполевые draft/editor/example/history DTO.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAdministrationTest.java` — обновить прямые SQL fixtures.
- `scripts/tests/curriculum-content.test.mjs` — валидировать трёхполевую структуру всех practice examples.

**Детали реализации**:

```java
private AssessmentDecision decide(ModelAssessment value) {
    Set<String> fields = new LinkedHashSet<>();
    if (questionFailed(value)
            || value.categoryFit().score() < 2
            || value.questionStrength().score() < 3) fields.add("question");
    if (rationaleContradicts(value)) fields.add("rationale");
    if (solutionFailed(value)) fields.add("solution");

    if ("LOW".equals(value.confidence())) return AssessmentDecision.unverified();
    return fields.isEmpty()
            ? AssessmentDecision.passed()
            : AssessmentDecision.needsRevision(List.copyOf(fields));
}
```

Дополнительные правила:

- `rationale=WEAK` не добавляется в `fieldsToRevise` и не мешает `PASSED`;
- `rationale=CONTRADICTS` добавляет только `rationale`, если остальные критерии прошли;
- category fit или question strength ниже порога добавляют `question`;
- solution `FAIL` добавляет `solution`;
- в `saveUnverified` сохраняются три серверных шага;
- новая минимальная длина: question `30`, rationale `40`, solution `35`; максимальная длина rationale `8000`, чтобы мигрированные тексты не требовали усечения;
- точные дубликаты проверяются только среди трёх новых значений;
- для старых schema v1 helper нормализует `answer/reasoning` в `rationale` в assessment steps и в revision scope. Если оба старых поля присутствуют в `fieldsToRevise`, наружу возвращается один `rationale`;
- удалить существующий дублирующий guard `if (!"UNVERIFIED".equals(latest.status()))` в `editableFields` при затрагивании метода, не меняя остальную retry-семантику.

**Зависит от**: шаги 1 и 2.

**Проверка**:

- `cd backend && mvn -Dtest='ru.questionhacker.trainer.practice.*Test' test`
- Сильные question/solution и `WEAK` rationale дают `PASSED`.
- `CONTRADICTS` даёт `NEEDS_REVISION` только для rationale.
- Старый v1 `NEEDS_REVISION` с `answer` и/или `reasoning` открывает один rationale.
- `UNVERIFIED` retry по-прежнему наследует область редактирования и допускает повтор без изменений.

---

### Шаг 4 — Перевести интерфейс на три поля (сложность: medium)

**Цель**: убрать пользовательскую двусмысленность и отобразить фактическую трёхполевую модель backend.

**Файлы**:

- `frontend/index.html` — заменить четырёхшаговые rails, форму и server example на «Вопрос / Обоснование / Решение»; удалить `practice-answer` и `practice-reasoning`, добавить `practice-rationale`.
- `frontend/app.js` — `FIELD_LABELS = {question, rationale, solution}`; обновить чтение/отправку draft, attempt, revision, retry, timeline, progress и focus-first-revision; исправить отображение шкал на category `/3` и question strength `/4`.
- `frontend/styles.css` — сделать progress и example/timeline сетки трёхшаговыми; сохранить визуальное выделение единственного редактируемого rationale.
- `frontend/tests/thin-client.test.mjs` — заменить четырёхполевые assertions и расширить retry/revision проверки для rationale; перед редактированием сверить актуальный `git diff`, чтобы сохранить параллельные пользовательские изменения.

**Детали реализации**:

```js
const FIELD_LABELS = {
  question: 'Вопрос',
  rationale: 'Обоснование',
  solution: 'Решение'
};

const minimums = { question: 30, rationale: 40, solution: 35 };
```

Текст под полем «Обоснование» должен прямо объяснять его роль: «Почему из вопроса следует это решение? Слабое объяснение даст рекомендацию; противоречие потребует исправления».

**Зависит от**: шаг 3.

**Проверка**:

- `node --test frontend/tests/*.test.mjs`
- В HTML и practice-коде отсутствуют `practice-answer` и `practice-reasoning`.
- Первичная отправка, revision и retry содержат ровно `question`, `rationale`, `solution`.
- В feedback показываются корректные шкалы `0…3` и `0…4`.

---

### Шаг 5 — Удалить legacy-колонки после переключения кода (сложность: medium)

**Цель**: завершить «правильную» миграцию и не оставлять двойную модель данных.

**Файлы**:

- `backend/src/main/resources/db/migration/V12__drop_legacy_practice_answer_reasoning.sql` — убедиться, что `rationale_text` заполнен, сделать его `NOT NULL`, удалить `answer_text` и `reasoning_text` из `practice_attempt`, `practice_draft`, `practice_example`.
- `backend/src/test/java/ru/questionhacker/trainer/ThreeFieldPracticeMigrationTest.java` — мигрировать V10 → latest и проверить отсутствие legacy-колонок и полную сохранность текстов/истории.
- `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java` — зафиксировать финальную трёхполевую схему.

**Детали реализации**:

```sql
ALTER TABLE practice_attempt ALTER COLUMN rationale_text SET NOT NULL;
ALTER TABLE practice_attempt DROP COLUMN answer_text;
ALTER TABLE practice_attempt DROP COLUMN reasoning_text;
-- Повторить для practice_draft и practice_example.
```

Исторические `practice_assessment.step_results_json`, `fields_to_revise_json`, `revised_fields_json` не переписываются: это audit JSON соответствующей версии. Их совместимость обеспечивается нормализатором из шага 3.

**Зависит от**: шаги 3 и 4.

**Проверка**:

- `cd backend && mvn -Dtest=ThreeFieldPracticeMigrationTest,MigrationTest test`
- В `INFORMATION_SCHEMA.COLUMNS` есть ровно `question_text`, `rationale_text`, `solution_text` для каждой practice content-таблицы.
- Старые циклы читаются через новый API без `answer/reasoning`.

---

### Шаг 6 — Закрыть сквозные регрессии и документацию (сложность: medium)

**Цель**: проверить все жизненные циклы практики и синхронизировать публичное описание продукта.

**Файлы**:

- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java` — сквозные сценарии `EVALUATING → PASSED/NEEDS_REVISION/UNVERIFIED`, derived revision fields, immutable parent history и idempotency.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java` — восстановление трёхполевого черновика после перезагрузки и история нескольких ревизий.
- `frontend/tests/thin-client.test.mjs` — проверка трёх шагов, подсветки editableFields и корректного endpoint для revision/retry.
- `README.md` — заменить описание «вопрос, ответ, рассуждение и решение» на «вопрос, обоснование и решение» и кратко описать мягкую роль обоснования.
- `docs/ARCHITECTURE.md` — зафиксировать контракт v2, серверный verdict и совместимость чтения v1. Исторические specs/plans не переписывать.

**Детали реализации**:

Обязательная матрица тестов:

```text
Сильный вопрос + WEAK rationale + подходящее решение       -> PASSED
Сильный вопрос + CONTRADICTS + подходящее решение          -> NEEDS_REVISION [rationale]
Слабая категория/сила вопроса + хорошее решение            -> NEEDS_REVISION [question]
Сильный вопрос + неподходящее решение                      -> NEEDS_REVISION [solution]
Несколько блокирующих дефектов                              -> список всех соответствующих полей
LOW confidence                                              -> UNVERIFIED + доступный retry
Старый v1 fieldsToRevise=[answer,reasoning]                 -> editableFields=[rationale]
```

**Зависит от**: шаги 1–5.

**Проверка**:

- `cd backend && mvn test`
- `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs`
- Поиск `rg -n 'practice-(answer|reasoning)|answer_text|reasoning_text' frontend backend/src README.md docs/ARCHITECTURE.md` не находит активного четырёхполевого контракта; допустимы только V5/V8 и v1 audit/prompt.

---

### Шаг 7 — Ручная приёмка полного цикла (сложность: low)

**Цель**: подтвердить поведение реального UI и сохранность данных вне unit/integration fixtures.

**Файлы**:

- Изменений файлов не требуется; используется локальный запуск и отдельная тестовая H2-база/копия данных.

**Детали реализации**:

1. Открыть существующий старый цикл и проверить объединённое «Обоснование».
2. Создать новую ситуацию, заполнить три поля и перезагрузить страницу до submit — черновик должен восстановиться.
3. Получить `PASSED` при слабом, но непротиворечивом rationale.
4. Получить `NEEDS_REVISION`, изменить только разрешённое поле и проверить новую карточку истории.
5. Отключить ACP, получить `UNVERIFIED`, восстановить ACP и выполнить retry без изменений.
6. Проверить, что старые и новые попытки остаются отдельными неизменяемыми записями.

**Зависит от**: шаги 1–6.

**Проверка**:

- `scripts/run-local.sh`
- В DevTools новые practice-запросы и ответы не содержат `answer`/`reasoning`.
- В UI нигде не остаётся четырёхшаговая терминология или неверные знаменатели шкал.

---

## Риски и способы их снижения

| Риск | Вероятность | Воздействие | Способ снижения |
|------|-------------|-------------|-----------------|
| Потеря старых answer/reasoning при миграции | med | high | Двухфазные V11/V12, точный migration test V10 → latest, запрет усечения текста |
| Старый `fieldsToRevise` открывает неверное поле | high | high | Версионный normalizer: `answer` или `reasoning` всегда отображаются как один `rationale`; backend остаётся единственным authority |
| Слабое обоснование снова случайно блокирует зачёт | med | high | Отдельный enum `SUPPORTS/WEAK/CONTRADICTS` и integration test `WEAK → PASSED` |
| Модель управляет доступностью полей | med | high | Удалить `fieldsToRevise` из v2 model contract и вычислять список на backend по фиксированным правилам |
| `LOW` confidence создаёт бесконечную бессмысленную правку | med | med | Направлять результат в существующий `UNVERIFIED` retry-поток |
| Новая версия prompt перезапишет старый аудит | low | high | Оставить prompt key, повысить version до 2, сохранить v1 resource и строки prompt_version |
| CurriculumImporter продолжит писать legacy-поля | med | high | Переключить importer и JSON examples до V12; отдельный curriculum test на поле rationale |
| Frontend и backend временно несовместимы | med | med | Разворачивать единым релизом; не заявлять обратную совместимость старого API |
| Потеря незакоммиченных пользовательских правок в затрагиваемых тестах | med | med | Перед каждым шагом повторно проверять `git status` и diff целевых файлов; накладывать изменения поверх актуальной версии |

## Критические точки

1. **Семантика `WEAK` против `CONTRADICTS`** должна быть закреплена prompt, parser и тестами до изменения verdict.
2. **V1 compatibility normalizer** обязателен до удаления колонок: без него старые `NEEDS_REVISION` и `UNVERIFIED` циклы могут стать недоступными для продолжения.
3. **V12 нельзя применять**, пока repository, importer и все прямые SQL fixtures не перестали обращаться к legacy-колонкам.
4. **Retry scope** должен остаться серверным и наследоваться через parent-chain; технический сбой не должен расширять доступность полей.
5. Рабочее дерево менялось параллельно во время планирования; на последней проверке пользовательские изменения находятся в `PracticeAttemptLifecycleTest.java`. Перед реализацией нужно заново определить актуальные dirty-файлы и не перезаписывать их.

## Итоговые критерии готовности

- [ ] `cd backend && mvn test` — все backend-тесты проходят.
- [ ] `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs` — все frontend/curriculum-тесты проходят.
- [ ] В активной схеме practice content хранится только в `question_text`, `rationale_text`, `solution_text`.
- [ ] Старые answer/reasoning объединены без потери текста и доступны как rationale.
- [ ] Все новые API DTO содержат только `question`, `rationale`, `solution`.
- [ ] `WEAK` rationale не мешает зачёту; `CONTRADICTS` открывает rationale для исправления.
- [ ] Category fit ниже 2 или question strength ниже 3 открывают question; провал решения открывает solution.
- [ ] `fieldsToRevise` v2 вычисляется backend, а не принимается от модели.
- [ ] `UNVERIFIED` retry работает без изменений и сохраняет унаследованную область редактирования.
- [ ] История попыток остаётся неизменяемой, parent links и idempotency сохранены.
- [ ] В UI три шага, корректные шкалы `/3` и `/4`, autosave, revision и retry работают.
- [ ] Новый функционал покрыт parser, migration, integration и frontend tests.
- [ ] Нет новых предупреждений компилятора и незапланированных изменений пользовательских файлов.
