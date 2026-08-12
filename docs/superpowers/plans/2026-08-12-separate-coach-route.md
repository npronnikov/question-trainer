# Separate Coach Route Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give structured practice and coach dialogue independent `#practice` and `#coach` routes while preserving their current in-memory state and shared model controls.

**Architecture:** Keep one learning view and make the hash route the source of truth for its visible region. The router maps both learning routes to the shared view, then a focused synchronizer owns route-specific headings, sidebar content, actions, inert state, and lazy chat initialization.

**Tech Stack:** Static HTML, vanilla JavaScript hash routing, CSS, Node's built-in test runner, Spring Boot/Maven verification.

---

### Task 1: Define the route contract with a failing frontend test

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Write the failing test**

Append this structural contract:

```js
test('practice and coach are independent hash routes', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /data-route="practice">Практика<\/button>/);
  assert.match(html, /data-route="coach">Коуч<\/button>/);
  assert.match(html, /id="view-learning"[^>]*data-view="learning"/);
  assert.doesNotMatch(html, /data-coach-mode/);
  assert.match(app, /\['practice', 'coach'\]\.includes\(route\) \? 'learning' : route/);
  assert.match(app, /syncLearningRoute\(route\)/);
  assert.doesNotMatch(app, /coachMode|setCoachMode/);
});
```

- [ ] **Step 2: Run the focused test file and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL in `practice and coach are independent hash routes` because `data-route="practice"` and `view-learning` do not exist yet.

- [ ] **Step 3: Commit only after the implementation in Task 2 is green**

Do not commit a deliberately failing tree. Keep this test unstaged while completing Task 2.

### Task 2: Make routes authoritative and split the learning interface

**Files:**
- Modify: `frontend/index.html:56-61`
- Modify: `frontend/index.html:138-180`
- Modify: `frontend/app.js:20-85`
- Modify: `frontend/app.js:480-495`
- Modify: `frontend/app.js:647-653`
- Modify: `frontend/app.js:930-950`
- Test: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Add independent top-level navigation and one shared learning view**

Replace the combined top-level route with:

```html
<button class="nav-link" data-route="practice">Практика</button>
<button class="nav-link" data-route="coach">Коуч</button>
```

Rename the combined view to:

```html
<section class="view" id="view-learning" data-view="learning">
```

Replace the internal mode switch with route-owned sidebar context:

```html
<div class="sidebar-head"><div><span class="eyebrow" id="learning-section-label">03 / ПРАКТИКА</span><h2 id="learning-sidebar-title">Полный цикл</h2></div></div>
<p class="sidebar-route-copy" id="practice-route-context">Четыре шага превращают вопрос в проверяемое решение.</p>
<div class="session-tools is-hidden" id="session-tools" inert>
  <button class="secondary-button" id="new-session" type="button">＋ Новый диалог</button>
  <div class="session-list" id="session-list"></div>
</div>
```

Keep the practice panel visible by default and the message feed/composer hidden by default.

- [ ] **Step 2: Replace mode state with route synchronization**

Remove `coachMode` and `setCoachMode`. Add:

```js
function syncLearningRoute(route) {
  const chat = route === 'coach';
  const practicePanel = $('#practice-panel');
  const messageFeed = $('#message-feed');
  const composer = $('#composer');
  const sessionTools = $('#session-tools');
  const practiceContext = $('#practice-route-context');

  practicePanel.classList.toggle('is-hidden', chat);
  practicePanel.inert = chat;
  $('#new-practice').classList.toggle('is-hidden', chat);
  messageFeed.classList.toggle('is-hidden', !chat);
  messageFeed.inert = !chat;
  composer.classList.toggle('is-hidden', !chat);
  composer.inert = !chat;
  sessionTools.classList.toggle('is-hidden', !chat);
  sessionTools.inert = !chat;
  practiceContext.classList.toggle('is-hidden', chat);
  practiceContext.inert = chat;
  $('#learning-section-label').textContent = chat ? '04 / КОУЧ' : '03 / ПРАКТИКА';
  $('#learning-sidebar-title').textContent = chat ? 'Диалоги' : 'Полный цикл';
  $('#chat-title').textContent = chat ? 'Тренер вопросов' : 'Практика полного цикла';
  if (chat) initChat();
}
```

Update `setRoute` so allowed routes include both route names and both activate the shared view:

```js
const allowed = admin
  ? ['theory', 'trainer', 'practice', 'coach', 'moderation']
  : ['theory', 'trainer', 'practice', 'coach'];
const route = allowed.includes(rawRoute) ? rawRoute : 'theory';
const viewRoute = ['practice', 'coach'].includes(route) ? 'learning' : route;
closeModelPicker();
$$('.view').forEach(view => view.classList.toggle('is-active', view.dataset.view === viewRoute));
$$('.nav-link').forEach(link => link.classList.toggle('is-active', link.dataset.route === route));
if (viewRoute === 'learning') syncLearningRoute(route);
```

Retain the trainer/moderation lazy-loading branches. Remove the old `route === 'coach' && coachMode === 'chat'` branch because `syncLearningRoute` now owns chat initialization.

- [ ] **Step 3: Remove the obsolete mode binding and boot default**

Change `bindPractice` to begin directly with the practice controls:

```js
function bindPractice() {
  $('#start-practice').addEventListener('click', startPractice);
  $('#new-practice').addEventListener('click', startPractice);
  $('#practice-form').addEventListener('submit', submitPractice);
  $('#practice-form').addEventListener('input', () => updatePracticeProgress());
  $('#model-select').addEventListener('change', event => { selectedModel = event.target.value || null; });
}
```

Remove `setCoachMode('practice')` from `boot`; `setRoute(location.hash.slice(1) || 'theory', false)` now establishes the initial learning route.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: all tests in the file PASS, including the new independent-route contract.

- [ ] **Step 5: Commit the routing behavior**

```bash
git add frontend/index.html frontend/app.js frontend/tests/thin-client.test.mjs
git commit -m "feat: split practice and coach routes"
```

### Task 3: Preserve the product styling and support narrow navigation

**Files:**
- Modify: `frontend/styles.css:80-90`
- Modify: `frontend/styles.css:278-315`
- Modify: `frontend/styles.css:454-490`
- Test: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Add a failing CSS contract to the route test**

Read `styles.css` in the existing test and add:

```js
const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');
assert.match(css, /#view-learning\s*\{/);
assert.match(css, /\.sidebar-route-copy\s*\{/);
assert.match(css, /@media \(max-width: 720px\)[\s\S]*\.main-nav\s*\{[^}]*overflow-x:\s*auto;/);
assert.doesNotMatch(css, /\.coach-mode-switch/);
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because `#view-learning`, `.sidebar-route-copy`, and mobile navigation overflow are not styled yet.

- [ ] **Step 3: Implement the focused CSS changes**

Rename the view selector, remove obsolete mode-switch rules, and add the route context:

```css
#view-learning { background: #e9e7dd; }
.sidebar-route-copy { margin: 0 8px 24px; color: #b8bab1; font-size: 13px; line-height: 1.55; }
```

At the `max-width: 720px` breakpoint, replace the fixed equal-width navigation treatment with:

```css
.main-nav {
  width: calc(100% - 28px);
  justify-content: flex-start;
  overflow-x: auto;
  scrollbar-width: none;
}
.main-nav::-webkit-scrollbar { display: none; }
.nav-link { flex: 0 0 auto; padding-inline: 12px; white-space: nowrap; }
```

Remove the obsolete mobile `.coach-mode-switch button` rule.

- [ ] **Step 4: Run the focused and complete frontend suites**

Run:

```bash
node --test frontend/tests/thin-client.test.mjs
node --test frontend/tests/*.test.mjs
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check scripts/dev-server.mjs
```

Expected: all tests and syntax checks PASS.

- [ ] **Step 5: Commit the styling**

```bash
git add frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "style: distinguish practice and coach navigation"
```

### Task 4: Verify integration and route lifecycle

**Files:**
- Verify: `frontend/index.html`
- Verify: `frontend/app.js`
- Verify: `frontend/styles.css`

- [ ] **Step 1: Run complete project verification**

Run:

```bash
cd backend && mvn test
node --test frontend/tests/*.test.mjs
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check scripts/dev-server.mjs
docker compose config
git diff --check
```

Expected: Maven reports 75 tests with zero failures/errors, frontend reports all tests passing, syntax checks are silent, Compose config exits zero, and `git diff --check` is silent.

- [ ] **Step 2: Verify both routes in the browser**

Using the authenticated `demo` session:

1. Open `http://localhost:8090/#practice` and confirm only practice controls are visible and `Практика` is active.
2. Enter text in the first practice field without submitting.
3. Open `#coach` and confirm sessions, feed, composer, and `Коуч` active state; practice controls must be absent from interaction.
4. Navigate back to `#practice` and confirm the typed text remains.
5. Use browser back/forward and confirm route-owned controls and active navigation follow the URL.
6. At a narrow viewport, confirm all navigation labels remain readable and horizontal overflow is available when required.

- [ ] **Step 3: Inspect final repository state**

Run:

```bash
git status -sb
git log -3 --oneline --decorate
```

Expected: the worktree is clean and the feature commits follow the design/plan commits.
