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
