# Scenario moderation queue implementation plan

**Goal:** Ensure generated scenarios can enter the published training pool only after deterministic screening and an explicit audited ADMIN decision.

**Architecture:** A generation gateway returns strict candidate JSON. The backend validates structure, known categories/difficulty, single-technique intent, hint leakage, unsafe terms and near-duplicates before persisting `PENDING_REVIEW` or `AUTO_REJECTED`. ADMIN endpoints list, edit, reject and approve candidates; approval transactionally creates the canonical scenario/options and marks the candidate `PUBLISHED`. A version column provides optimistic concurrency and every mutation writes an immutable audit action.

## Task 1: Add queue and audit schema

- Flyway V6 creates `scenario_candidate` and `moderation_action` with lifecycle constraints, version, payload, rejection/warning metadata and actor audit.
- Extend migration tests and commit.

## Task 2: Add strict generation and automatic screening

- Add versioned generator prompt/gateway and strict candidate DTO parser.
- Auto-reject malformed values, unknown categories/difficulty, multiple primary techniques, answer leakage in hints, unsafe terms, and exact/high-similarity duplicates.
- Persist rejected candidates with machine-readable reasons; only clean candidates enter `PENDING_REVIEW`.
- Test every rejection family and commit.

## Task 3: Add ADMIN queue and moderation actions

- Add `/api/admin/scenario-candidates` list/detail/generate/edit/reject/approve endpoints.
- Require rejection reason, rerun filters after edit, and require matching expected version for every state change.
- Approval transactionally publishes a canonical scenario with four options and correct answer; rejected/auto-rejected candidates can never publish.
- Record actor, action, previous/new status/version and optional comment.
- Test role enforcement, audit and concurrent decisions; commit.

## Task 4: Phase acceptance

- Run all backend tests/package and HTTP smoke tests as USER and ADMIN.
- Verify published count increases only after approval and that rejected candidates never appear in trainer selection.
- Leave the tree clean before thin-frontend migration.
