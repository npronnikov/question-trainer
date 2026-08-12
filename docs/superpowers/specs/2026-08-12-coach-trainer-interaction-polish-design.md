# Coach and trainer interaction polish

## Scope

Polish the `#coach` and `#trainer` interfaces without changing practice APIs, scoring, curriculum selection, authentication, or server ownership of progress.

The change has three outcomes:

1. Move the four-step thought sequence into the coach practice introduction.
2. Replace the visually inconsistent native model select with a custom coach popover.
3. Replace the stacked trainer result panel with the back face of the existing question card.

## Interface direction

The learner is moving deliberately through a reasoning exercise, not configuring a generic dashboard. The interface should feel like a marked-up workbook: dark graphite workspace, warm paper cards, an acid highlighter for active progress, violet annotation for reframing cues, and restrained orange only for warnings or fallback state.

Relevant domain concepts are question, answer, reasoning, solution, frame, operation, category, evidence, and learning trajectory. The signature element is the four-stage thought rail: `Вопрос → Ответ → Рассуждение → Решение`.

The design rejects three current defaults:

- A loose eyebrow in the coach header becomes a meaningful progress rail inside the exercise introduction.
- A native operating-system select becomes a product-owned model trigger and popover.
- A second result card below the trainer question becomes the reverse side of the same learning card.

Use the existing typography, spacing scale, graphite/paper/acid palette, border radii, and subtle-shadow depth strategy. This is a focused extension of the current design system, not a restyle.

## Coach practice introduction

- Remove `ВОПРОС → ОТВЕТ → РАССУЖДЕНИЕ → РЕШЕНИЕ` from the coach header.
- Render the sequence between `Пройдите полный ход мысли.` and its explanatory paragraph.
- Represent the sequence as four equal rail segments with an uppercase monospace label and a thin top rule.
- In the empty state, highlight `Вопрос` with the acid accent and leave later stages in graphite.
- Once a practice scenario is active, reuse the existing practice progress state to highlight completed/current stages. The sequence remains descriptive; it does not become clickable navigation.
- On narrow screens, retain four columns when labels fit. At the smallest breakpoint, allow a two-column wrap without horizontal scrolling.

## Coach model popover

Replace the native `<select>` presentation with a custom control while retaining a hidden native select or equivalent form value as the single source of truth.

The trigger contains:

- A compact circular model mark.
- A readable model family name on the first line.
- Reasoning level and short positioning text on the second line.
- A chevron that reflects open state.

The popover contains all models returned by `/api/system/status`. Each option shows the same two-line information hierarchy and marks the selected option. The control replaces only the coach model select; the trainer difficulty selector is unrelated and remains unchanged.

Required states are closed, open, hover, keyboard focus, selected, disabled, and no-models. The popover closes on selection, Escape, outside click, route change, and loss of availability. Arrow Up/Down moves the active option, Enter/Space selects, and focus returns to the trigger after Escape.

The existing selected-model state remains authoritative. Choosing an option updates that state and the coach trigger. Raw model identifiers continue to be sent to the backend; friendly labels are presentation only.

## Trainer card flip

- Keep one `.trainer-card` layout slot and one stable stage height.
- Place the question form on the front face and the server review on the back face.
- After a successful review response, populate the back face, then add the flipped state so the card rotates 180 degrees around the Y axis.
- Do not insert the review as a separate block in normal document flow.
- Preserve the existing result content: verdict, score, explanation, contrast, next step, `Следующая карточка`, and `Открыть теорию`.
- `Следующая карточка` first removes the flipped state, then replaces the front-face scenario after the reverse transition completes. This prevents the incoming question from flashing through the rotating card.
- While the review request is pending, keep the front face visible and show its busy state. On request failure, remain on the front face and render the existing form error; never flip to an empty result.
- With `prefers-reduced-motion: reduce`, change faces without 3D animation while preserving the same visible-state and accessibility semantics.

Only the active face participates in interaction and the accessibility tree. Use `aria-hidden` and inert/disabled descendants so hidden controls cannot receive focus. After a flip, move focus to the result heading; after the next card loads, move focus to the scenario heading or first meaningful front-face element.

## Component boundaries and data flow

`frontend/index.html` owns stable semantic shells for the thought rail, coach model picker, and both trainer faces. `frontend/app.js` owns state transitions and synchronized attributes. `frontend/styles.css` owns elevation, responsive layout, popover placement, and flip motion.

Trainer flow:

1. The learner selects a category, writes a rationale, and submits.
2. Existing API code sends the attempt and receives the review.
3. Review rendering populates the back face without changing layout flow.
4. The card enters the flipped state and focus moves to the result.
5. The next-card action returns to the front, loads the next server card, resets form state, and restores focus.

Model flow:

1. System status supplies raw model identifiers and the default.
2. A formatting helper derives family, reasoning level, and secondary copy.
3. Selecting any popover option updates the shared selected-model value.
4. The coach trigger rerenders its selected state and subsequent requests send the unchanged raw identifier.

## Verification and acceptance criteria

- Structural frontend tests prove the coach header no longer contains the thought sequence and the practice introduction does.
- Component tests prove the model popover opens, supports keyboard selection, updates the coach value, closes correctly, and handles disabled/no-model states.
- Trainer behavior tests prove a successful review flips the existing card, review content is not appended beneath it, failures keep the front visible, and next-card sequencing resets the face.
- CSS/DOM assertions cover reduced motion, inactive-face accessibility, and responsive thought-rail wrapping.
- `node --check frontend/app.js`, the complete frontend test command, and `mvn test` must pass.
- Browser verification on `http://localhost:18090/#coach` and `#trainer` must confirm visual hierarchy, open-popover layering, real keyboard focus, flip motion, reduced-motion fallback, and narrow-screen layout.

The approved reference mockup is stored in the ignored brainstorming workspace under `.superpowers/brainstorm/85674-1786551287/content/final-direction.html`.
