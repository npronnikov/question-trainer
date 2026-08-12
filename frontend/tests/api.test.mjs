import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const apiSourceUrl = new URL('../api.js', import.meta.url);

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 401 ? 'Unauthorized' : 'OK',
    async json() { return body; }
  };
}

async function loadApi(fetchImpl) {
  const source = await fs.readFile(apiSourceUrl, 'utf8');
  const window = {};
  const context = vm.createContext({ window, fetch: fetchImpl, Headers, URLSearchParams });
  vm.runInContext(source, context, { filename: 'frontend/api.js' });
  return window.QH_API;
}

test('mutation gets CSRF first and sends same-origin credentials and issued header', async () => {
  const calls = [];
  const api = await loadApi(async (path, options) => {
    calls.push({ path, options });
    if (path === '/api/auth/csrf') {
      return jsonResponse(200, { headerName: 'X-CSRF-TOKEN', token: 'issued-token' });
    }
    return jsonResponse(200, { saved: true });
  });

  const result = await api.request('/api/example', {
    method: 'POST',
    body: JSON.stringify({ answer: 42 })
  });

  assert.deepEqual(result, { saved: true });
  assert.equal(calls.length, 2);
  assert.equal(calls[0].path, '/api/auth/csrf');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[1].path, '/api/example');
  assert.equal(calls[1].options.credentials, 'same-origin');
  assert.equal(calls[1].options.headers.get('X-CSRF-TOKEN'), 'issued-token');
  assert.equal(calls[1].options.headers.get('Content-Type'), 'application/json');
});

test('401 response exposes server problem details and notifies the auth shell', async () => {
  let unauthorizedStatus = null;
  const api = await loadApi(async () => jsonResponse(401, {
    title: 'Authentication required',
    detail: 'Войдите, чтобы продолжить'
  }));
  api.onUnauthorized(error => { unauthorizedStatus = error.status; });

  await assert.rejects(
    api.request('/api/auth/me'),
    error => error.status === 401
      && error.message === 'Войдите, чтобы продолжить'
      && error.problem.title === 'Authentication required'
  );
  assert.equal(unauthorizedStatus, 401);
});
