# Separate coach route

## Scope

Separate structured full-cycle practice from free-form coach dialogue at the routing and navigation level without changing backend APIs, authentication, model availability, practice assessment, or chat persistence.

The change has three outcomes:

1. Make `#practice` the dedicated route for the four-step practice workflow.
2. Make `#coach` the dedicated route for coach conversations.
3. Remove the internal practice/chat mode switch so the URL and top navigation are the single source of truth.

## Interface direction

The learner has two distinct ways to work: a prescribed reasoning sequence and an open conversation. The interface should present them as neighboring destinations, not as hidden modes of one page.

Relevant domain concepts are question, answer, reasoning, solution, learning trajectory, dialogue, session history, coach, agent, and model. The existing color world remains graphite ink, warm paper, acid highlighter, violet annotation, and restrained orange fallback signals. The signature element is the top-level split between `Практика` and `Коуч`: one destination leads through the four-step thought rail, while the other opens an ongoing conversation.

The design rejects three defaults:

- A local segmented mode switch becomes explicit application navigation.
- Two duplicated page implementations become one shared learning shell driven by route state.
- Route-specific controls that merely disappear become route-specific regions with clear ownership and accessibility state.

Use the existing typography, spacing, palette, model popover, sidebar, and responsive navigation behavior. This is an information-architecture change, not a visual restyle.

## Routes and navigation

The authenticated top navigation contains these learner routes in order:

1. `Теория` → `#theory`
2. `Тренажёр` → `#trainer`
3. `Практика` → `#practice`
4. `Коуч` → `#coach`

The administrator-only `Модерация` route remains after the learner routes. The existing hash router continues to validate routes by role, update the active navigation item, handle browser back/forward navigation, and scroll a newly selected route to the top.

`#practice` is the canonical URL for structured practice. `#coach` is the canonical URL for conversations. Unknown or unauthorized routes continue to fall back to `#theory`. No redirect from the former combined meaning of `#coach` is required because `#coach` remains valid and now consistently means dialogue.

On narrow screens, the navigation remains a single compact bar. It may scroll horizontally when all learner routes do not fit; labels must not wrap or collapse into ambiguous icons.

## Shared shell and route ownership

Keep one learning shell in `frontend/index.html` to avoid duplicating the model picker, ACP status, and layout structure. Both route values map to this shared view, while the route determines which regions participate in layout and interaction.

The route replaces the mutable `coachMode` as the source of truth:

- `#practice` activates the practice region.
- `#coach` activates the dialogue region.
- Route changes update visibility, route-specific actions, headings, sidebar contents, and accessibility attributes in one synchronization function.

The removed internal mode switch must not leave a second, competing navigation mechanism. The sidebar becomes route-specific content rather than a route selector.

## Practice route

`#practice` shows:

- The heading `Практика полного цикла`.
- The custom model picker used for assessment requests.
- The `Новая ситуация` action.
- The empty-state thought rail or the current practice workspace.
- The ACP status card, which explains semantic assessment availability.

`#practice` hides and removes from keyboard interaction:

- Chat session history.
- New-dialog controls.
- Message feed.
- Message composer.

Navigating away does not discard an issued assignment or partially entered practice fields. Returning to `#practice` restores the same in-memory DOM state until the page is reloaded.

## Coach route

`#coach` shows:

- The heading `Тренер вопросов`.
- The custom model picker used for chat messages.
- The new-dialog control and the learner's session history.
- The message feed and composer.
- The ACP status card.

`#coach` hides and removes from keyboard interaction:

- Practice empty state and workspace.
- New-situation controls.
- Practice form and feedback.

Opening `#coach` lazily initializes chat history. Re-entering the route reuses the initialized state and current session. Failures continue to render through the existing chat empty/error states; routing does not add a second error channel.

## State, accessibility, and lifecycle

The current hash route is authoritative. Route synchronization must:

1. Activate the shared learning view for both `practice` and `coach`.
2. Mark exactly one top-navigation item active.
3. Show only the regions and actions owned by that route.
4. Set hidden route regions so their controls cannot receive focus.
5. Close the model popover before changing visible regions.
6. Initialize chat only when entering `#coach`.
7. Preserve practice and chat state when moving between the two routes.

Existing authentication boot behavior remains unchanged. After login, the requested hash is resolved normally. Logout still hides the application shell and clears API authentication state.

## Component boundaries

- `frontend/index.html` owns the two top-level navigation entries and stable shared-shell regions.
- `frontend/app.js` owns route validation, mapping both route values to the shared view, route-specific visibility, lazy chat initialization, and history navigation.
- `frontend/styles.css` owns compact four-item learner navigation, horizontal overflow on narrow screens, and route-specific sidebar/layout presentation.
- `frontend/tests/thin-client.test.mjs` owns structural regression coverage for route semantics and removal of the internal mode switch.

No backend, database, service-worker, or API contract changes are required.

## Verification and acceptance criteria

- `#practice` activates the structured practice region and the `Практика` navigation item.
- `#coach` activates the dialogue region and the `Коуч` navigation item.
- The internal `Практика / Диалог с коучем` switch no longer exists.
- Browser back and forward move between the two routes without losing current in-memory practice or chat state.
- Chat initialization is triggered by `#coach`, never by `#practice`.
- Hidden route regions are absent from keyboard interaction and the accessibility tree.
- The model picker remains available and sends the same raw model identifier from both workflows.
- The administrator-only moderation route and role gate remain unchanged.
- Desktop and narrow-screen navigation expose all learner routes with readable, non-wrapping labels.
- A new frontend regression test fails before implementation and passes after the route split.
- `node --test frontend/tests/*.test.mjs`, all frontend syntax checks, and `mvn test` pass.
- Browser verification on `http://localhost:8090/#practice` and `http://localhost:8090/#coach` confirms correct active navigation, route-owned controls, back/forward behavior, and preserved in-memory state.
