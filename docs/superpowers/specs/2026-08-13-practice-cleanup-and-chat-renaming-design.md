# Practice cleanup and chat renaming design

## Goal

Simplify the Practice navigation and add server-owned chat naming without moving domain state into the browser.

## Practice changes

- Remove the `Обзор` button and the associated return-to-overview behavior.
- Remove the decorative `04` from the Practice introduction.
- Remove the visible cycle-count/status text from the left rail.
- Remove the explanatory text `Четыре шага превращают вопрос в проверяемое решение` from the left rail.
- Keep the cycle list and the `Новая ситуация` action.
- `Новая ситуация` must continue to request a new assignment from the backend. It does not generate content. The backend selects an already published scenario, preferring one the current user has not received before and then applying its existing difficulty ordering.
- Scenario generation remains an admin moderation workflow driven by `prompts/scenario-candidates-v1.md`; only published scenarios are eligible for Practice.

## Chat automatic title

The backend remains the authority for chat titles.

When the first user message is sent to a session whose title is exactly `Новый диалог`, the backend will:

1. trim the message and collapse whitespace to single spaces;
2. take at most the first 30 Unicode code points;
3. append `...`;
4. persist the result as the session title before starting the coach run.

Short messages also receive the `...` suffix. A manually renamed session is never overwritten by first-message naming because it no longer has the sentinel title `Новый диалог`.

## Manual chat rename

Each chat row will contain a pencil control next to the delete control. Activating it replaces the title display with an inline text field.

- `Enter` saves.
- `Escape` cancels and restores the previous title.
- Losing focus does not silently persist; it cancels unless a save is already in progress.
- A successful save updates the list from the server response.
- A failed save restores editing and shows the server error without losing the entered value.

The frontend calls a new owner-scoped endpoint:

`PATCH /api/chat/sessions/{sessionId}`

Request:

```json
{"title":"Новый заголовок"}
```

The backend trims the title, rejects blank values, enforces the existing 180-character storage limit, verifies ownership, updates `updated_at`, and returns the updated session. A user cannot discover or rename another user's session.

## Frontend boundaries

The frontend owns only temporary editing state and keyboard interaction. It does not infer ownership, persist titles locally, or compute automatic titles. All durable state and naming rules remain on the backend.

## Error handling

- Missing or foreign sessions return the same `404` response.
- Blank or oversized titles return `400` validation errors.
- While a rename request is pending, duplicate submissions are blocked.
- Existing delete and session-selection controls remain available outside the row currently being edited.

## Verification

Backend tests cover:

- automatic title generation from the first message;
- the 30-code-point limit and `...` suffix;
- whitespace normalization and Unicode safety;
- successful owner rename;
- blank and oversized titles;
- rejection of a foreign session rename;
- preservation of a manual title when messages are sent.

Frontend tests cover:

- removal of the Practice overview controls and requested text;
- the pencil control and inline edit interaction;
- `Enter` save and `Escape` cancel;
- use of the server rename endpoint;
- absence of frontend automatic-title logic.

