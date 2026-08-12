import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const frontend = new URL('../', import.meta.url);

test('authentication shell is the gate in front of the learning application', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');

  assert.match(html, /id="auth-view"/);
  assert.match(html, /id="login-form"/);
  assert.match(html, /id="register-form"/);
  assert.match(html, /id="app-shell"[^>]*hidden/);
  assert.ok(html.indexOf('api.js') < html.indexOf('auth.js'));
  assert.ok(html.indexOf('auth.js') < html.indexOf('app.js'));
});

test('application boot is exported and is not invoked eagerly', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const auth = await fs.readFile(new URL('auth.js', frontend), 'utf8');

  assert.match(app, /window\.QH_APP\s*=/);
  assert.match(auth, /window\.QH_AUTH\s*=/);
  assert.doesNotMatch(app, /\n\s*boot\(\);\s*\n\}\)\(\);\s*$/);
  assert.doesNotMatch(app, /API_BASE/);
});
