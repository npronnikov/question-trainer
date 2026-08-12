# Thin Frontend Migration Plan

> **Goal:** make the browser a presentation layer for the server-owned curriculum, adaptive trainer, four-step assessment, progress, and scenario moderation workflow.

## Architecture boundary

- The browser may keep ephemeral UI state only: selected route, opened category, in-flight identifiers, textarea values, and the selected presentation filter.
- Curriculum, scenarios, correct answers, scoring, mastery, attempt lifecycle, revision permissions, moderation status, and publishing decisions come exclusively from `/api/**`.
- Authentication remains the application gate. ADMIN navigation is derived from the authenticated user roles returned by the server.
- The existing ink/paper/acid visual system remains. The signature interaction is the visible four-step path: question → answer → reasoning → solution.

## Task 1: Lock the frontend contract with tests

**Files:**
- Modify: `frontend/tests/auth-shell.test.mjs`
- Create: `frontend/tests/thin-client.test.mjs`

Acceptance:
- HTML contains four labelled practice fields and a moderation view.
- Static curriculum/scenario scripts and domain localStorage are absent.
- Frontend source calls the versioned server endpoints for curriculum, trainer, progress, practice, and moderation.
- Trainer requires a rationale and feedback is an accessible live region.

## Task 2: Replace static theory and local trainer state

**Files:**
- Modify: `frontend/index.html`
- Replace: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/sw.js`

Acceptance:
- Categories and category detail load from `/api/curriculum/categories` and `/api/curriculum/categories/{code}`.
- `/api/trainer/next` supplies the card and options; `/api/trainer/attempts` decides correctness.
- The page shows server mastery, accuracy, recommendation, and confusion evidence from `/api/progress`.
- No correct answer or score is computed in the browser.

## Task 3: Implement the four-step assessment workspace

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

Acceptance:
- Assignment is created by `/api/practice/assignments`.
- Submission includes question, answer, reasoning, solution, model, and an idempotency key.
- Browser follows the async attempt until a terminal status and renders all three semantic dimensions plus the backend verdict.
- Revision mode enables only `fieldsToRevise`; unchanged protected fields are not sent.
- Feedback is focused and announced; low-motion preference removes flips and smooth scrolling.

## Task 4: Add the ADMIN moderation gate

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

Acceptance:
- ADMIN users can open the queue, generate candidates, filter statuses, inspect automatic rejection reasons, edit pending drafts, approve, and reject with a reason.
- Non-admin users do not see or initialize moderation controls.
- Generation never adds cards directly to the trainer; only server publication does.

## Task 5: Retire bypass APIs and verify the whole application

**Files:**
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiController.java`
- Modify or remove legacy scenario/practice services only if no longer referenced.
- Modify: `README.md`

Acceptance:
- Legacy `/api/scenarios/generate`, `/api/scenarios/generated`, `/api/practice/scenario`, and `/api/practice/review` routes are absent.
- Frontend Node tests, full Gradle tests, and packaged JAR succeed.
- Built application starts; registration/login, curriculum, trainer, practice submission, progress, and admin authorization receive expected HTTP responses.
- Browser QA covers desktop and narrow viewport, keyboard focus, and reduced motion.
