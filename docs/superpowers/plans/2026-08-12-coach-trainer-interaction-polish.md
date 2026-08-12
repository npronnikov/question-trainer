# Coach and Trainer Interaction Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the coach thought sequence into the practice introduction, replace the coach native model select with an accessible custom popover, and turn the trainer result into the back face of the existing card.

**Architecture:** Keep the existing server-owned learning data and vanilla-JavaScript application shell. Add stable semantic shells in `index.html`, small state-transition helpers in `app.js`, and product-specific component styling in `styles.css`; verify contracts with the existing dependency-free Node structural tests and real browser interaction.

**Tech Stack:** HTML5, CSS 3D transforms, vanilla JavaScript, Node.js `node:test`, Spring Boot backend regression suite.

---

## File map

- `frontend/index.html` — coach thought rail, accessible model trigger/listbox shell, and trainer front/back faces.
- `frontend/app.js` — model popover state/keyboard behavior and trainer face transitions/focus management.
- `frontend/styles.css` — model elevation and states, stable card stage and 3D flip, responsive and reduced-motion behavior.
- `frontend/tests/thin-client.test.mjs` — structural and interaction-contract regression tests.

### Task 1: Lock the approved DOM and accessibility contracts

**Files:**

- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Add failing structural tests**

Add tests that read `index.html`, `app.js`, and `styles.css` and assert:

```js
test('coach thought rail lives inside the practice introduction', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const header = html.match(/<header class="chat-header">[\s\S]*?<\/header>/)?.[0] || '';
  const empty = html.match(/<div class="practice-empty"[\s\S]*?<\/div>\s*<article class="practice-workspace/)?.[0] || '';
  assert.doesNotMatch(header, /ВОПРОС → ОТВЕТ → РАССУЖДЕНИЕ → РЕШЕНИЕ/);
  assert.match(empty, /class="practice-progress practice-progress-intro"/);
  for (const label of ['Вопрос', 'Ответ', 'Рассуждение', 'Решение']) assert.match(empty, new RegExp(label));
});

test('coach model picker is an accessible custom popover', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  assert.match(html, /id="model-trigger"[^>]*aria-haspopup="listbox"[^>]*aria-expanded="false"/);
  assert.match(html, /id="model-popover"[^>]*role="listbox"[^>]*hidden/);
  assert.match(html, /id="model-select"[^>]*hidden/);
  assert.match(app, /ArrowDown|ArrowUp/);
  assert.match(app, /Escape/);
  assert.match(app, /closeModelPicker/);
});

test('trainer feedback is the inert back face of one flippable card', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');
  assert.match(html, /id="trainer-card"[\s\S]*class="trainer-card-inner"[\s\S]*id="trainer-front"[\s\S]*id="trainer-feedback"[^>]*aria-hidden="true"[^>]*inert/);
  assert.match(app, /classList\.toggle\('is-flipped'/);
  assert.match(app, /\.inert\s*=/);
  assert.match(css, /\.trainer-card\.is-flipped\s+\.trainer-card-inner/);
  assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*\.trainer-card-inner/);
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because the thought rail is absent from the empty state, the native select is still visible, and feedback is a separate document-flow card.

- [ ] **Step 3: Commit the failing tests together with the subsequent minimal implementations, not separately**

The repository history should not stop on a knowingly red commit.

### Task 2: Move the coach sequence and implement the model popover

**Files:**

- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Test: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Move the thought sequence into the empty practice introduction**

Replace the header eyebrow with only the title and insert after the empty-state heading:

```html
<div class="practice-progress practice-progress-intro" aria-label="Полный ход мысли">
  <span class="is-active">Вопрос</span><span>Ответ</span><span>Рассуждение</span><span>Решение</span>
</div>
```

Keep the existing workspace `.practice-progress`; it represents live stage completion after a scenario starts.

- [ ] **Step 2: Replace the native model presentation with a semantic trigger and listbox**

Use this stable shell in `index.html`:

```html
<div class="model-picker" id="model-picker">
  <span class="model-picker-label">Модель</span>
  <button class="model-trigger" id="model-trigger" type="button" aria-haspopup="listbox" aria-expanded="false" aria-controls="model-popover" disabled>
    <span class="model-mark" id="model-mark">—</span>
    <span class="model-copy"><strong id="model-name">Модель недоступна</strong><small id="model-detail">Нет доступных моделей</small></span>
    <span class="model-chevron" aria-hidden="true">⌄</span>
  </button>
  <div class="model-popover" id="model-popover" role="listbox" aria-label="Модель коуча" hidden></div>
  <select id="model-select" aria-hidden="true" tabindex="-1" hidden disabled><option>—</option></select>
</div>
```

- [ ] **Step 3: Add focused model helpers and events in `app.js`**

Implement:

```js
function modelPresentation(model) {
  const match = String(model || '').match(/^(.+?)(?:\[(x?high)\])?$/i);
  const rawFamily = match?.[1] || 'Модель';
  const reasoning = match?.[2]?.toLowerCase();
  const family = rawFamily.replace(/^gpt-/i, 'GPT-').replace(/-([a-z])/g, (_, letter) => ` ${letter.toUpperCase()}`);
  return {
    family,
    mark: rawFamily.split('-').at(-1)?.charAt(0).toUpperCase() || '?',
    detail: reasoning === 'xhigh' ? 'Extra high · максимум анализа' : reasoning === 'high' ? 'High · сбалансировано' : 'Стандартный режим'
  };
}

function setSelectedModel(model) {
  selectedModel = model || null;
  $('#model-select').value = selectedModel || '';
  renderModelPicker();
}

function openModelPicker(focusIndex = null) { /* set hidden=false and aria-expanded=true; optionally focus an option */ }
function closeModelPicker({ restoreFocus = false } = {}) { /* hide list, reset aria-expanded, optionally focus trigger */ }
function moveModelFocus(currentIndex, delta) { /* wrap across enabled option buttons */ }
```

Render escaped option values as buttons with `role="option"`, `aria-selected`, and `data-model`. Bind click, Arrow Up/Down, Home/End, Enter/Space, and Escape. Bind the trigger, document outside click, and route changes; `loadSystemStatus()` must populate the hidden select then call `setSelectedModel()`.

- [ ] **Step 4: Style the model trigger and elevated popover**

Use a positioned `.model-picker`, a two-line `.model-trigger`, a graphite circular `.model-mark`, and a one-level-higher paper `.model-popover` with subtle shadow. Add explicit hover, focus-visible, selected, disabled, and open-chevron states. At the mobile breakpoint, preserve the readable name and hide only `.model-picker-label`.

- [ ] **Step 5: Run the focused test and verify the coach contracts are GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: coach thought-rail and model-popover tests PASS; trainer flip test may remain red until Task 3.

### Task 3: Turn trainer feedback into the card back face

**Files:**

- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Test: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Wrap the existing trainer form and feedback as two faces**

Use this hierarchy without duplicating learning content:

```html
<div class="trainer-card" id="trainer-card" aria-busy="true">
  <div class="trainer-card-inner">
    <article class="trainer-face trainer-card-front" id="trainer-front" aria-hidden="false">
      <!-- existing question form -->
    </article>
    <article class="trainer-face trainer-card-back" id="trainer-feedback" role="status" aria-live="polite" aria-hidden="true" inert></article>
  </div>
</div>
```

- [ ] **Step 2: Centralize active-face semantics**

Implement:

```js
function setTrainerFace(flipped) {
  const card = $('#trainer-card');
  const front = $('#trainer-front');
  const back = $('#trainer-feedback');
  card.classList.toggle('is-flipped', flipped);
  front.setAttribute('aria-hidden', String(flipped));
  back.setAttribute('aria-hidden', String(!flipped));
  front.inert = flipped;
  back.inert = !flipped;
}

function waitForTrainerTurn() {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return Promise.resolve();
  return new Promise(resolve => {
    const inner = $('.trainer-card-inner');
    const fallback = window.setTimeout(resolve, 700);
    inner.addEventListener('transitionend', () => { window.clearTimeout(fallback); resolve(); }, { once: true });
  });
}
```

Make `loadTrainerCard()` return to the front and await the reverse turn only when currently flipped. After successful review rendering, call `setTrainerFace(true)` and focus `#trainer-result-title`. Request errors must leave the front face active.

- [ ] **Step 3: Keep the next-card sequence visually atomic**

Bind `#next-card` to an async handler that calls `setTrainerFace(false)`, awaits `waitForTrainerTurn()`, then fetches and renders the next issuance. Difficulty changes use the same reset path. The front card must not be replaced during the reverse transition.

- [ ] **Step 4: Replace stacked-card CSS with an overlaid 3D stage**

Define `.trainer-card-inner` as a single grid with `transform-style: preserve-3d`; both `.trainer-face` elements occupy `grid-area: 1 / 1` and use `backface-visibility: hidden`. Rotate the back face by 180 degrees and the inner wrapper when `.trainer-card.is-flipped`. Keep surface padding and green/orange verdict backgrounds on the faces.

In the existing reduced-motion block, add:

```css
.trainer-card-inner { transition: none; }
.trainer-card.is-flipped .trainer-card-front { visibility: hidden; }
.trainer-card.is-flipped .trainer-card-back { visibility: visible; }
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: all tests in the file PASS.

### Task 4: Complete regression and browser verification

**Files:**

- Modify only if verification finds a scoped defect: `frontend/index.html`, `frontend/app.js`, `frontend/styles.css`, `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Run syntax and all frontend tests**

Run:

```bash
node --check frontend/app.js
node --test frontend/tests/*.test.mjs
```

Expected: syntax check exits 0 and every frontend test passes.

- [ ] **Step 2: Run backend regression tests**

Run: `cd backend && mvn test`

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 3: Verify the live coach interface**

Reload `http://localhost:18090/#coach` from the `main` process. Confirm the sequence is absent from the header and present under the empty-state heading. Open the model picker, use Arrow Down/Up and Enter, close with Escape, click outside, and confirm the raw selected model is used in a new practice request.

- [ ] **Step 4: Verify the live trainer interface**

Submit a valid category and rationale. Confirm the original card turns to the server result without a second card below, hidden-face controls cannot receive focus, next-card waits for the reverse turn, and request errors do not flip.

- [ ] **Step 5: Verify responsive and reduced-motion modes**

At a narrow viewport, confirm the thought rail wraps without horizontal scrolling and the model trigger remains legible. Emulate reduced motion and confirm an immediate, non-animated face change.

- [ ] **Step 6: Review the final diff and commit**

Run: `git diff --check && git status --short`

Then commit only the planned frontend/test changes:

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs docs/superpowers/plans/2026-08-12-coach-trainer-interaction-polish.md
git commit -m "feat: polish coach and trainer interactions"
```
