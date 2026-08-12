# Server curriculum and adaptive trainer implementation plan

**Goal:** Make Spring Boot the only source of curriculum, trainer cards, correctness, mastery, confusion and next-card selection.

**Architecture:** Versioned Flyway tables hold canonical category/card data and per-user learning state. A startup importer reads reviewed JSON resources idempotently. REST endpoints return screen-ready view models that never expose the correct answer before submission. `TrainerEngine` owns deterministic scoring and weighted selection; the browser only renders responses and submits user intent.

**Tech stack:** Java 21, Spring Boot 3.5, JDBC, Flyway, H2, Jackson, JUnit 5, MockMvc.

---

## Task 1: Add curriculum and learning-state schema

**Files:**

- Create `backend/src/main/resources/db/migration/V4__curriculum_and_trainer.sql`
- Modify `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`

1. Add a failing migration test for category, theory, evidence, scenario/options, issuance, trainer attempt, mastery and directed confusion tables.
2. Add constraints for known evidence/difficulty values, immutable card correctness, unique category code, unique external card key and one active issuance per id.
3. Add owner foreign keys and indexes used by next-card selection/progress.
4. Run `mvn -Dtest=MigrationTest test` and commit.

## Task 2: Import the reviewed curriculum into backend resources

**Files:**

- Create `backend/src/main/resources/curriculum/categories.json`
- Create `backend/src/main/resources/curriculum/scenarios.json`
- Create `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumImporter.java`
- Create `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumRepository.java`
- Test `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumImporterTest.java`

1. Convert the seven existing categories to canonical codes, mapping `FUTURISM` to `BACKCASTING`; attach `RESEARCH_SUPPORTED`, `PRACTITIONER_METHOD` or `HEURISTIC` to claims/sources.
2. Import the existing 56 cards as `L1` and add reviewed L2/L3 cards until there are exactly 98 (14/category); every L3 includes a confusion pair and contrast explanation.
3. Make import idempotent and fail startup on invalid category counts, duplicated keys, missing options/answer, or invalid L3 contrast metadata.
4. Test exact counts, mapping and repeat import, then commit.

## Task 3: Expose curriculum screen models

**Files:**

- Create `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumService.java`
- Create `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumController.java`
- Test `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumControllerTest.java`

1. Add failing authenticated API tests for category list/detail, theory/evidence labels and contrast table.
2. Return cautious server-authored claims and source links; keep card answer data out of curriculum responses.
3. Require authentication through the existing security rule and return Problem Details for unknown codes.
4. Run focused tests and commit.

## Task 4: Issue trainer cards without leaking answers

**Files:**

- Create `backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerRepository.java`
- Create `backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerEngine.java`
- Create `backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerController.java`
- Test `backend/src/test/java/ru/questionhacker/trainer/trainer/TrainerIssuanceTest.java`

1. Test `GET /api/trainer/next` returns issuance id, situation, question, options and difficulty, but not correct category/explanation.
2. Bind every issuance to the current user with expiry and reject foreign/expired/replayed issuance ids.
3. Support optional requested difficulty only as a hint; backend retains final selection.
4. Run focused tests and commit.

## Task 5: Score attempts, rationale, mastery and confusion on backend

**Files:**

- Modify trainer repository/engine/controller
- Test `backend/src/test/java/ru/questionhacker/trainer/trainer/TrainerAttemptTest.java`

1. Require nonblank rationale, store it, and prove rationale text never changes correctness or score.
2. Return correctness, correct category, operation explanation, selected-vs-correct contrast and recommended next step.
3. Apply difficulty-weighted bounded mastery updates; one error cannot erase stable mastery.
4. Increment directed `selected instead of correct` confusion only on wrong answers and make duplicate submission idempotent.
5. Run focused tests and commit.

## Task 6: Implement adaptive selection and progress view models

**Files:**

- Modify trainer engine/repository/controller
- Test `backend/src/test/java/ru/questionhacker/trainer/trainer/AdaptiveSelectionTest.java`

1. Isolate selection scoring behind an injected random source and test the 50/30/20 weak/confusion/review branches deterministically.
2. Include correctness, difficulty, recency and recent-card diversity in ranking.
3. Add `GET /api/progress` returning category percentages/levels, directed confusion pairs and one server-authored recommendation.
4. Verify progress persists across sessions for the same user and remains isolated between users; commit.

## Task 7: Regression and phase acceptance

1. Run `mvn test` and `mvn package`.
2. Smoke-test register/login, curriculum, next-card, rationale submission and progress over the same-origin proxy.
3. Verify the next-card payload has no answer fields before submission and exactly 98 published cards exist in the imported pool.
4. Confirm `git status --short` is clean before starting the practice-assessment plan.
