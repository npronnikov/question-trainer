import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const frontend = new URL('../', import.meta.url);

test('practice is a labelled four-step server assessment', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');

  for (const id of ['practice-question', 'practice-answer', 'practice-reasoning', 'practice-solution']) {
    assert.match(html, new RegExp(`for="${id}"`));
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(html, /id="practice-feedback"[^>]*role="status"/);
  assert.match(html, /id="trainer-rationale"/);
});

test('practice exposes server history, worked example, timeline, and draft status regions', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /id="practice-history-tools"/);
  assert.match(html, /id="practice-cycle-list"[^>]*role="list"/);
  assert.match(html, /id="practice-example"/);
  for (const field of ['question', 'answer', 'reasoning', 'solution']) {
    assert.match(html, new RegExp(`id="practice-example-${field}"`));
  }
  assert.match(html, /id="practice-example-recommendation"/);
  assert.match(html, /id="practice-timeline"/);
  assert.match(html, /id="practice-save-status"[^>]*aria-live="polite"/);
  assert.doesNotMatch(app, /localStorage|indexedDB/);
});

test('browser contains no curriculum, answer, scoring, or mastery authority', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const worker = await fs.readFile(new URL('sw.js', frontend), 'utf8');

  assert.doesNotMatch(html, /data\/(theory|scenarios)\.js/);
  assert.doesNotMatch(worker, /data\/(theory|scenarios)\.js/);
  assert.doesNotMatch(app, /QH_CATEGORIES|QH_SCENARIOS|loadProgress|saveProgress|qh-progress/);
  assert.doesNotMatch(app, /selectedId\s*===\s*trainerCard\.category/);
});

test('thin client delegates every learning workflow to server endpoints', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  for (const endpoint of [
    '/curriculum/categories', '/trainer/next', '/trainer/attempts', '/progress',
    '/practice/assignments', '/practice/attempts', '/admin/scenario-candidates'
  ]) {
    assert.match(app, new RegExp(endpoint.replaceAll('/', '\\/')));
  }
  assert.doesNotMatch(app, /\/practice\/(scenario|review)/);
  assert.doesNotMatch(app, /\/scenarios\/generate/);
});

test('moderation is a role-gated application view', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /data-route="moderation"[^>]*hidden/);
  assert.match(html, /id="view-moderation"/);
  assert.match(app, /roles\?\.includes\('ADMIN'\)/);
});

test('reduced motion and focusable feedback are explicit', async () => {
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');

  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.match(html, /id="practice-feedback"[^>]*tabindex="-1"/);
  assert.match(html, /id="trainer-feedback"[^>]*tabindex="-1"/);
});

test('theory omits the server-program footer and evidence uses two content columns', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');

  assert.doesNotMatch(html, /СЕРВЕРНАЯ ПРОГРАММА/);
  assert.match(app, /class="evidence-meta"[\s\S]*section\.source[\s\S]*<\/div><p>/);
  assert.match(css, /\.evidence-card\s*\{[^}]*grid-template-columns:\s*minmax\(180px,\s*\.7fr\)\s+1\.5fr;/);
});

test('theory shows a complete worked example before expandable historical cases', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');

  assert.match(app, /class="worked-example"/);
  assert.match(app, /ordinaryQuestion/);
  assert.match(app, /hackerQuestion/);
  assert.match(app, /reasoningSteps\.map/);
  assert.match(app, /questionTemplates\.slice\(0, 2\)/);
  assert.match(app, /class="exercise-pair applied-exercises"/);
  assert.match(app, /<details class="case-card"/);
  assert.match(app, /item\.sources\.map/);
  assert.match(app, /Подтверждено исследованием/);
  assert.match(app, /Ретроспективная интерпретация/);
  assert.match(css, /\.worked-questions\s*\{[^}]*grid-template-columns:\s*1fr 1fr/);
  assert.match(css, /@media \(max-width:\s*720px\)[\s\S]*\.worked-questions[^{]*\{[^}]*grid-template-columns:\s*1fr/);
});

test('lower ACP card opens an accessible diagnostic dialog', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /<button[^>]*id="agent-status-card"[^>]*aria-haspopup="dialog"/);
  assert.match(html, /<dialog[^>]*id="acp-status-dialog"[^>]*aria-labelledby="acp-dialog-title"/);
  assert.match(html, /id="acp-dialog-reason"/);
  assert.match(app, /status\.acpAvailable/);
  assert.match(app, /#agent-status-card[\s\S]*showModal/);
});

test('coach thought rail lives inside the practice introduction', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const header = html.match(/<header class="chat-header">[\s\S]*?<\/header>/)?.[0] || '';
  const empty = html.match(/<div class="practice-empty"[\s\S]*?<\/div>\s*<article class="practice-workspace/)?.[0] || '';

  assert.doesNotMatch(header, /ВОПРОС → ОТВЕТ → РАССУЖДЕНИЕ → РЕШЕНИЕ/);
  assert.match(empty, /class="practice-progress practice-progress-intro"/);
  for (const label of ['Вопрос', 'Ответ', 'Рассуждение', 'Решение']) {
    assert.match(empty, new RegExp(label));
  }
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

test('loading the next trainer card restores the front submit control', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /function renderTrainerCard[\s\S]*setBusy\(\$\('#submit-trainer'\), false, 'Проверить на сервере →'\)/);
});

test('practice progress updates only the active form rail', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /id="practice-workspace-progress"[^>]*aria-label="Этапы практики"/);
  assert.match(app, /\$\$\('#practice-workspace-progress span'\)/);
  assert.doesNotMatch(app, /\$\$\('\.practice-progress span'\)/);
});

test('practice hydrates server cycles and debounces owner draft persistence', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /api\('\/practice\/cycles'\)/);
  assert.match(app, /api\('\/practice\/examples\/random'\)/);
  assert.match(app, /`\/practice\/cycles\/\$\{assignmentId\}`/);
  assert.match(app, /`\/practice\/cycles\/\$\{practiceAssignment\.assignmentId\}\/draft`/);
  assert.match(app, /practiceLoadSequence/);
  assert.match(app, /practiceDraftTimer/);
  assert.match(app, /window\.setTimeout\([\s\S]*650\)/);
  assert.match(app, /setAttribute\('aria-current'/);
  assert.match(app, /classList\.toggle\('is-practice'/);
});

test('practice locks submitted values and guards duplicate mutations', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /let practiceSubmitting = false/);
  assert.match(app, /if \(practiceSubmitting\) return/);
  assert.match(app, /setRevisionFields\(\[\], true\)/);
  assert.match(app, /buttons\.some\(button => button\.disabled\)/);
});

test('trainer loads ignore stale responses and clear controls on failure', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /trainerLoadSequence/);
  assert.match(app, /if \(sequence !== trainerLoadSequence\) return/);
  assert.match(app, /function renderTrainerLoadError/);
});

test('model popover closes when keyboard focus leaves the picker', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(app, /addEventListener\('focusout'/);
  assert.match(app, /document\.activeElement/);
});

test('practice and coach are independent hash routes', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');

  assert.match(html, /data-route="practice">Практика<\/button>/);
  assert.match(html, /data-route="coach">Коуч<\/button>/);
  assert.match(html, /id="view-learning"[^>]*data-view="learning"/);
  assert.doesNotMatch(html, /data-coach-mode/);
  assert.match(app, /\['practice', 'coach'\]\.includes\(route\) \? 'learning' : route/);
  assert.match(app, /syncLearningRoute\(route\)/);
  assert.doesNotMatch(app, /coachMode|setCoachMode/);
  assert.match(css, /#view-learning\s*\{/);
  assert.match(css, /\.sidebar-route-copy\s*\{/);
  assert.match(css, /@media \(max-width: 720px\)[\s\S]*\.main-nav\s*\{[^}]*overflow-x:\s*auto;/);
  assert.doesNotMatch(css, /\.coach-mode-switch/);
});

test('practice history invalidates the offline shell cache', async () => {
  const serviceWorker = await fs.readFile(new URL('sw.js', frontend), 'utf8');

  assert.match(serviceWorker, /const CACHE = 'question-hacker-v12';/);
});

test('boot activates the hash route before background API hydration', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const routeActivation = app.lastIndexOf("setRoute(location.hash.slice(1) || 'theory', false);");
  const backgroundHydration = app.lastIndexOf('await Promise.allSettled([loadCurriculum(), refreshProgressView(), loadSystemStatus()]);');

  assert.ok(routeActivation > -1);
  assert.ok(backgroundHydration > -1);
  assert.ok(routeActivation < backgroundHydration);
});
