import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, copyFile, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const sourceScript = new URL('../run-local.sh', import.meta.url);

async function fixture(codexExitCode) {
  const root = await mkdtemp(join(tmpdir(), 'question-trainer-run-local-'));
  const scripts = join(root, 'scripts');
  const codex = join(root, 'configured-codex');
  await mkdir(scripts);
  await copyFile(sourceScript, join(scripts, 'run-local.sh'));
  await chmod(join(scripts, 'run-local.sh'), 0o755);
  await writeFile(codex, `#!/usr/bin/env bash\nexit ${codexExitCode}\n`);
  await chmod(codex, 0o755);
  await writeFile(join(root, '.env'), `CODEX_PATH='${codex}'\n`);
  return { root, codex };
}

function checkConfiguration(root, overrides = {}) {
  const env = { ...process.env, PATH: '/usr/bin:/bin', ...overrides };
  delete env.CODEX_PATH;
  return spawnSync('/bin/bash', [join(root, 'scripts/run-local.sh'), '--check-config'], {
    cwd: root,
    env,
    encoding: 'utf8'
  });
}

test('local runner loads a working CODEX_PATH from the root .env', async t => {
  const { root, codex } = await fixture(0);
  t.after(() => rm(root, { recursive: true, force: true }));

  const result = checkConfiguration(root);

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, new RegExp(`Локальный Codex: ${codex.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
});

test('local runner rejects a configured Codex binary that cannot start', async t => {
  const { root } = await fixture(1);
  t.after(() => rm(root, { recursive: true, force: true }));

  const result = checkConfiguration(root);

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Настроенный CODEX_PATH не запускается/);
});
