# Mobile Bottom Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the horizontally scrolling mobile navigation capsule with an accessible, safe-area-aware bottom tab bar while preserving desktop navigation and existing routes.

**Architecture:** Keep `.main-nav` and `data-route` as the single navigation source. Add presentational icon/label spans to the existing buttons, synchronize `aria-current` in `setRoute`, and transform the same element into a bottom tab bar only inside the existing `max-width: 720px` breakpoint. Source-level Node tests protect markup, state synchronization, CSS geometry, and the service-worker cache version.

**Tech Stack:** Static HTML, vanilla JavaScript, CSS media queries/custom properties, Node.js built-in test runner.

---

### Task 1: Navigation markup and current-route semantics

**Files:**
- Create: `frontend/tests/mobile-navigation.test.mjs`
- Modify: `frontend/index.html:56-62`
- Modify: `frontend/app.js:112-124`

- [ ] **Step 1: Write the failing structure and state test**

Create `frontend/tests/mobile-navigation.test.mjs`:

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const frontend = new URL('../', import.meta.url);

test('primary routes expose labelled mobile tab icons', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const expected = [
    ['theory', '?', 'Теория'],
    ['trainer', '◇', 'Тренажёр'],
    ['practice', '↗', 'Практика'],
    ['coach', '✦', 'Коуч']
  ];

  for (const [route, icon, label] of expected) {
    const button = new RegExp(
      `<button class="nav-link(?: is-active)?" data-route="${route}"[^>]*>`
      + `[\\s\\S]*?<span class="nav-icon" aria-hidden="true">${icon.replace('?', '\\?')}</span>`
      + `[\\s\\S]*?<span class="nav-label">${label}</span>`
      + `[\\s\\S]*?</button>`
    );
    assert.match(html, button);
  }

  assert.match(html, /data-route="moderation"[^>]*aria-label="Модерация"[^>]*hidden/);
  assert.match(html, /class="nav-label nav-label-wide">Модерация</);
  assert.match(html, /class="nav-label nav-label-compact" aria-hidden="true">Модер\.<\/span>/);
});

test('route changes synchronize visual and semantic current state', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /const active = link\.dataset\.route === route/);
  assert.match(app, /link\.classList\.toggle\('is-active', active\)/);
  assert.match(app, /link\.toggleAttribute\('aria-current', active\)/);
  assert.match(app, /if \(active\) link\.setAttribute\('aria-current', 'page'\)/);
});
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
node --test frontend/tests/mobile-navigation.test.mjs
```

Expected: FAIL because `.nav-icon`, `.nav-label`, compact moderation markup, and `aria-current` synchronization do not exist.

- [ ] **Step 3: Add icons and labels to the existing navigation**

Replace the contents of `.main-nav` in `frontend/index.html` with:

```html
<nav class="main-nav" aria-label="Разделы">
  <button class="nav-link is-active" data-route="theory"><span class="nav-icon" aria-hidden="true">?</span><span class="nav-label">Теория</span></button>
  <button class="nav-link" data-route="trainer"><span class="nav-icon" aria-hidden="true">◇</span><span class="nav-label">Тренажёр</span></button>
  <button class="nav-link" data-route="practice"><span class="nav-icon" aria-hidden="true">↗</span><span class="nav-label">Практика</span></button>
  <button class="nav-link" data-route="coach"><span class="nav-icon" aria-hidden="true">✦</span><span class="nav-label">Коуч</span></button>
  <button class="nav-link" data-route="moderation" aria-label="Модерация" hidden><span class="nav-icon" aria-hidden="true">⚑</span><span class="nav-label nav-label-wide">Модерация</span><span class="nav-label nav-label-compact" aria-hidden="true">Модер.</span></button>
</nav>
```

- [ ] **Step 4: Synchronize `aria-current` in `setRoute`**

Replace the one-line `.nav-link` update in `frontend/app.js` with:

```js
$$('.nav-link').forEach(link => {
  const active = link.dataset.route === route;
  link.classList.toggle('is-active', active);
  link.toggleAttribute('aria-current', active);
  if (active) link.setAttribute('aria-current', 'page');
});
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
node --test frontend/tests/mobile-navigation.test.mjs
node --check frontend/app.js
```

Expected: 2 tests PASS and syntax check exits 0.

- [ ] **Step 6: Commit the semantic navigation change**

```bash
git add frontend/index.html frontend/app.js frontend/tests/mobile-navigation.test.mjs
git commit -m "feat: add semantic mobile navigation tabs"
```

### Task 2: Mobile bottom-bar layout and viewport safety

**Files:**
- Modify: `frontend/tests/mobile-navigation.test.mjs`
- Modify: `frontend/tests/thin-client.test.mjs:334-348`
- Modify: `frontend/styles.css:15-18,81-83,605-729`

- [ ] **Step 1: Add the failing responsive-layout test**

Append to `frontend/tests/mobile-navigation.test.mjs`:

```js
test('mobile navigation is a safe-area bottom bar without horizontal scrolling', async () => {
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');
  const mobile = css.slice(css.indexOf('@media (max-width: 720px)'), css.indexOf('@media (prefers-reduced-motion: reduce)'));

  assert.match(css, /--mobile-nav-height:\s*78px/);
  assert.match(css, /\.nav-icon\s*\{[^}]*display:\s*none/);
  assert.match(mobile, /\.main-nav\s*\{[^}]*left:\s*0;[^}]*right:\s*0;[^}]*bottom:\s*0;[^}]*width:\s*100%/);
  assert.doesNotMatch(mobile, /\.main-nav\s*\{[^}]*overflow-x:\s*auto/);
  assert.match(mobile, /env\(safe-area-inset-bottom\)/);
  assert.match(mobile, /\.nav-link\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px/);
  assert.match(mobile, /\.nav-link\.is-active \.nav-icon\s*\{[^}]*background:\s*var\(--ink\)/);
  assert.match(mobile, /\.composer\s*\{[^}]*bottom:\s*calc\(var\(--mobile-nav-offset\) \+ 8px\)/);
  assert.match(mobile, /100dvh/);
});
```

In the existing `practice and coach are independent hash routes` test, replace the old assertion that requires `overflow-x: auto` with:

```js
assert.doesNotMatch(css, /@media \(max-width: 720px\)[\s\S]*\.main-nav\s*\{[^}]*overflow-x:\s*auto;/);
```

- [ ] **Step 2: Run the responsive tests and verify RED**

Run:

```bash
node --test frontend/tests/mobile-navigation.test.mjs frontend/tests/thin-client.test.mjs
```

Expected: FAIL because the mobile rule still scrolls horizontally and lacks the shared height, safe-area offset, tab geometry, active icon, composer offset, and `dvh` sizing.

- [ ] **Step 3: Declare shared navigation dimensions and desktop icon visibility**

Add to `:root` in `frontend/styles.css`:

```css
--mobile-nav-height: 78px;
--mobile-nav-offset: calc(var(--mobile-nav-height) + env(safe-area-inset-bottom, 0px));
```

Extend the base navigation rules with:

```css
.nav-icon { display: none; }
.nav-label-compact { display: none; }
```

- [ ] **Step 4: Replace the `max-width: 720px` navigation rules**

Replace the mobile `.main-nav`, scrollbar, and `.nav-link` rules with:

```css
.main-nav {
  left: 0; right: 0; bottom: 0; transform: none;
  width: 100%; min-height: var(--mobile-nav-offset);
  justify-content: stretch; gap: 2px;
  padding: 7px 6px calc(7px + env(safe-area-inset-bottom, 0px));
  border-width: 1px 0 0; border-radius: 0;
  background: rgba(241,240,232,.96); box-shadow: 0 -12px 32px rgba(16,17,15,.12);
  overflow: visible;
}
.nav-link {
  min-width: 0; min-height: 44px; flex: 1 1 0;
  display: grid; grid-template-rows: 27px auto; place-items: center; gap: 3px;
  padding: 5px 2px 4px; border-radius: 14px;
  color: var(--muted); font-size: 9px; line-height: 1; white-space: nowrap;
}
.nav-icon {
  width: 27px; height: 27px; display: grid; place-items: center;
  border-radius: 50%; color: var(--ink); font-size: 16px; font-weight: 850;
}
.nav-link.is-active { background: rgba(217,255,83,.54); color: var(--ink); }
.nav-link.is-active .nav-icon { background: var(--ink); color: var(--acid); transform: rotate(-4deg); }
.nav-link:active { transform: translateY(1px); }
.nav-link[data-route="moderation"] .nav-label-wide { display: none; }
.nav-link[data-route="moderation"] .nav-label-compact { display: block; }
```

- [ ] **Step 5: Move content and composer above the panel**

Inside `@media (max-width: 720px)`, use `100dvh` after each `100vh` fallback and update fixed elements:

```css
.view { min-height: calc(100vh - 66px); min-height: calc(100dvh - 66px); padding-bottom: var(--mobile-nav-offset); }
#view-learning.is-practice .chat-main { height: auto; min-height: calc(100vh - 78px); min-height: calc(100dvh - 78px); }
.chat-main { height: calc(100vh - 66px); height: calc(100dvh - 66px); }
.message-feed { padding: 28px 17px calc(var(--mobile-nav-offset) + 108px); }
.practice-panel { height: calc(100vh - 140px); height: calc(100dvh - 140px); padding: 24px 17px calc(var(--mobile-nav-offset) + 32px); }
.composer { position: fixed; left: 14px; right: 14px; bottom: calc(var(--mobile-nav-offset) + 8px); margin: 0; }
.trainer-shell, .moderation-shell { padding-bottom: calc(var(--mobile-nav-offset) + 28px); }
```

- [ ] **Step 6: Run focused and full frontend tests**

Run:

```bash
node --test frontend/tests/mobile-navigation.test.mjs frontend/tests/thin-client.test.mjs
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
```

Expected: all tests PASS.

- [ ] **Step 7: Commit responsive styles**

```bash
git add frontend/styles.css frontend/tests/mobile-navigation.test.mjs frontend/tests/thin-client.test.mjs
git commit -m "feat: add mobile bottom navigation bar"
```

### Task 3: Refresh the offline shell

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs:394-400`
- Modify: `frontend/sw.js:1`

- [ ] **Step 1: Update the cache-version expectation first**

Change the service-worker assertion to:

```js
assert.match(serviceWorker, /const CACHE = 'question-hacker-v23';/);
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
node --test frontend/tests/thin-client.test.mjs
```

Expected: FAIL because `frontend/sw.js` still declares `question-hacker-v22`.

- [ ] **Step 3: Bump the offline cache**

Change the first line of `frontend/sw.js` to:

```js
const CACHE = 'question-hacker-v23';
```

- [ ] **Step 4: Run tests and syntax checks**

Run:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check frontend/sw.js
```

Expected: all tests PASS and every syntax check exits 0.

- [ ] **Step 5: Commit the cache refresh**

```bash
git add frontend/sw.js frontend/tests/thin-client.test.mjs
git commit -m "chore: refresh offline shell for mobile navigation"
```

### Task 4: Visual and regression verification

**Files:**
- Verify: `frontend/index.html`
- Verify: `frontend/styles.css`
- Verify: `frontend/app.js`

- [ ] **Step 1: State the interface checkpoint before visual verification**

Use this implementation intent:

```text
Intent: a learner switches among four training modes one-handed; navigation should feel energetic and unmistakably QUESTION / HACK.
Palette: existing paper, ink, acid, violet, and orange tokens; no new arbitrary colors.
Depth: subtle shadow plus one quiet top border because the panel floats above content without becoming a separate visual world.
Surfaces: existing paper surface at 96% opacity over the content.
Typography: existing sans labels and bold symbolic marks; no new font dependency.
Spacing: 4px base unit, with a minimum 44px touch target.
```

- [ ] **Step 2: Verify mobile widths**

Open the running application and inspect 320, 390, 430, and 720 px widths. For every width verify:

- four user routes fit without horizontal scrolling;
- the active route has an acid background and black/acid icon;
- switching `practice`/`coach` updates the active tab although both use `#view-learning`;
- the coach composer sits above the panel;
- long theory/trainer/practice/moderation content is not hidden behind the panel;
- focus-visible is clear on every tab.

- [ ] **Step 3: Verify the administrative layout**

With an administrator session at 320 px, confirm the fifth `Модер.` item is visible, all five targets remain usable, and moderation loads through the existing role gate.

- [ ] **Step 4: Run final verification from a clean command invocation**

Run:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check frontend/sw.js
git diff --check
git status --short
```

Expected: tests and syntax checks PASS, `git diff --check` prints nothing, and status contains no unintended files.
