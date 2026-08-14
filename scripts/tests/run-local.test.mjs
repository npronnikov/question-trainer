import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { chmod, copyFile, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';

const sourceScript = new URL('../run-local.sh', import.meta.url);

async function fixture(codexExitCode) {
  const root = await mkdtemp(join(tmpdir(), 'question-trainer-run-local-'));
  const scripts = join(root, 'scripts');
  const codex = join(root, 'configured-codex');
  await mkdir(scripts);
  await mkdir(join(root, 'backend'));
  await copyFile(sourceScript, join(scripts, 'run-local.sh'));
  await chmod(join(scripts, 'run-local.sh'), 0o755);
  await writeFile(codex, `#!/usr/bin/env bash\nexit ${codexExitCode}\n`);
  await chmod(codex, 0o755);
  await writeFile(join(root, '.env'), `CODEX_PATH='${codex}'\n`);
  return { root, codex };
}

async function startListener() {
  const child = spawn(process.execPath, [
    '-e',
    `require('node:net').createServer().listen(0, '127.0.0.1', function () {
      process.stdout.write(String(this.address().port));
    });`
  ], { stdio: ['ignore', 'pipe', 'inherit'] });
  const [data] = await once(child.stdout, 'data');
  return { child, port: Number(data.toString()) };
}

async function waitForExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;

  let timeout;
  try {
    await Promise.race([
      once(child, 'exit'),
      new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error(`PID ${child.pid} не завершился`)), 2_000);
      })
    ]);
  } finally {
    clearTimeout(timeout);
  }
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

test('local runner stops listeners on backend and frontend ports before launch', async t => {
  const { root } = await fixture(0);
  const bin = join(root, 'bin');
  const backendListener = await startListener();
  const frontendListener = await startListener();
  t.after(async () => {
    backendListener.child.kill('SIGKILL');
    frontendListener.child.kill('SIGKILL');
    await rm(root, { recursive: true, force: true });
  });

  await mkdir(bin);
  for (const command of ['mvn', 'node']) {
    const executable = join(bin, command);
    await writeFile(executable, '#!/usr/bin/env bash\nexit 0\n');
    await chmod(executable, 0o755);
  }

  const env = {
    ...process.env,
    PATH: `${bin}:/usr/bin:/bin:/usr/sbin:/sbin`,
    SERVER_PORT: String(backendListener.port),
    FRONTEND_PORT: String(frontendListener.port)
  };
  delete env.CODEX_PATH;
  const result = spawnSync('/bin/bash', [join(root, 'scripts/run-local.sh')], {
    cwd: root,
    env,
    encoding: 'utf8'
  });

  await Promise.all([
    waitForExit(backendListener.child),
    waitForExit(frontendListener.child)
  ]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, new RegExp(`Освобождаем порт ${backendListener.port}`));
  assert.match(result.stdout, new RegExp(`Освобождаем порт ${frontendListener.port}`));
  assert.equal(backendListener.child.signalCode, 'SIGTERM');
  assert.equal(frontendListener.child.signalCode, 'SIGTERM');
});
