(() => {
  'use strict';

  const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
  let csrf = null;
  let unauthorizedHandler = null;

  async function readBody(response) {
    if (response.status === 204) return null;
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  function problemError(response, problem) {
    const message = problem?.detail || problem?.message || problem?.title
      || `${response.status} ${response.statusText}`;
    const error = new Error(message);
    error.name = 'ApiError';
    error.status = response.status;
    error.problem = problem || {};
    return error;
  }

  async function send(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      credentials: 'same-origin'
    });
    const body = await readBody(response);
    if (!response.ok) {
      const error = problemError(response, body);
      if (response.status === 401 && unauthorizedHandler) unauthorizedHandler(error);
      throw error;
    }
    return body;
  }

  async function getCsrf() {
    if (!csrf) csrf = await send('/api/auth/csrf');
    return csrf;
  }

  async function request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    if (options.body != null && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    headers.set('Accept', 'application/json');

    if (MUTATING_METHODS.has(method)) {
      const token = await getCsrf();
      headers.set(token.headerName, token.token);
    }

    try {
      return await send(path, { ...options, method, headers });
    } catch (error) {
      if (error.status === 403 && MUTATING_METHODS.has(method)) csrf = null;
      throw error;
    }
  }

  window.QH_API = Object.freeze({
    request,
    resetCsrf() { csrf = null; },
    async refreshCsrf() {
      csrf = null;
      return getCsrf();
    },
    onUnauthorized(handler) { unauthorizedHandler = typeof handler === 'function' ? handler : null; }
  });
})();
