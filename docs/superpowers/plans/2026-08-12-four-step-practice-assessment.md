# Four-step practice assessment implementation plan

**Goal:** Replace the synchronous two-field heuristic practice with a user-owned, asynchronous four-step assessment whose final verdict is computed and enforced by the backend.

**Architecture:** Flyway tables hold assignments, immutable attempts, prompt versions and validated assessments. A dedicated practice package issues server-owned situations, performs deterministic completeness checks, asks ACP for a strict versioned JSON assessment, validates evidence/scores, and computes `PASSED`, `NEEDS_REVISION` or `UNVERIFIED` independently of the model. Polling and SSE expose the same persisted screen model; the old heuristic routes are retired after the thin frontend migrates.

**Prompt basis:** Preserve the useful coaching rules from `npronnikov/qbot/prompts/analysis.md`: evaluate the question and answer rather than the objective quality of the idea, state category expectations, never invent strengths, give one concrete correction, and remain respectful. Extend them to the reasoning and solution steps and to the approved category-specific rubric.

## Task 1: Persist assignments, attempts and versioned assessments

- Add Flyway V5 with `prompt_version`, `practice_assignment`, `practice_attempt` and `practice_assessment`.
- Enforce owner foreign keys, immutable parent chain, one assessment per attempt, legal lifecycle/verdict/score/confidence values and useful owner/status indexes.
- Extend migration tests and commit.

## Task 2: Issue server-owned practice assignments

- Add repository/service/controller under a dedicated `practice` package.
- `POST /api/practice/assignments` selects a published scenario and returns assignment id, situation, target category and server-authored category guidance without exposing card correctness metadata.
- Test authentication, ownership and requested-category validation; commit.

## Task 3: Define and validate the strict assessment contract

- Add a versioned prompt resource incorporating the referenced qbot coaching rules and all four steps.
- Add model DTO/parser/validator for completeness, category fit `0..3`, strength `0..4`, evidence, confidence, correction and fields to revise.
- Reject unknown enums, out-of-range scores, missing evidence, invalid correction fields and inconsistent completeness.
- Test valid and adversarial model JSON; commit.

## Task 4: Run asynchronous assessment and compute the verdict on backend

- `POST /api/practice/attempts` accepts assignment id plus question, answer, reasoning and solution; creates `EVALUATING` and returns immediately.
- Perform deterministic blank/length/duplicate checks before semantic scoring.
- Run the bounded assessment job on the existing virtual-thread executor; persist model id, prompt/schema versions, latency and verified/unverified outcome.
- Compute verdict server-side only: complete + fit >= 2 + strength >= 3 + confidence not LOW => `PASSED`; validated failures => `NEEDS_REVISION`; model/timeout/schema failure => `UNVERIFIED`.
- Test that a model-provided verdict cannot override thresholds and fallback never passes; commit.

## Task 5: Expose polling, history, revisions and SSE completion

- `GET /api/practice/attempts/{id}` returns the persisted owner-only screen model.
- A revision references its parent, inherits assignment and prior feedback, and permits editing only server-listed weak fields.
- `GET /api/practice/attempts/{id}/events` publishes the same terminal model as polling.
- Test ownership, revision rules, idempotent completion and terminal lifecycle; commit.

## Task 6: Phase acceptance

- Run full backend tests and package.
- Smoke-test assignment, four-field submission, polling/SSE and each terminal verdict.
- Confirm no heuristic can produce semantic scores or `PASSED`, then leave the tree clean before moderation work.
