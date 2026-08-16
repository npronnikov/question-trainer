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
    const escapedIcon = icon === '?' ? '\\?' : icon;
    const button = new RegExp(
      `<button class="nav-link(?: is-active)?" data-route="${route}"[^>]*>`
      + `[\\s\\S]*?<span class="nav-icon" aria-hidden="true">${escapedIcon}</span>`
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

test('mobile navigation is a safe-area bottom bar without horizontal scrolling', async () => {
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');
  const mobile = css.slice(css.indexOf('@media (max-width: 720px)'), css.indexOf('@media (prefers-reduced-motion: reduce)'));

  assert.match(css, /--mobile-nav-height:\s*78px/);
  assert.match(css, /\.nav-icon\s*\{[^}]*display:\s*none/);
  assert.match(mobile, /\.topbar\s*\{[^}]*backdrop-filter:\s*none/);
  assert.match(mobile, /\.main-nav\s*\{[^}]*left:\s*0;[^}]*right:\s*0;[^}]*bottom:\s*0;[^}]*width:\s*100%/);
  assert.doesNotMatch(mobile, /\.main-nav\s*\{[^}]*overflow-x:\s*auto/);
  assert.match(mobile, /env\(safe-area-inset-bottom/);
  assert.match(mobile, /\.nav-link\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px/);
  assert.match(mobile, /\.nav-link\.is-active \.nav-icon\s*\{[^}]*background:\s*var\(--ink\)/);
  assert.match(mobile, /\.composer\s*\{[^}]*bottom:\s*calc\(var\(--mobile-nav-offset\) \+ 8px\)/);
  assert.match(mobile, /100dvh/);
});
