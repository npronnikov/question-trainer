(() => {
  'use strict';

  let currentUser = null;
  let lastUserId = null;
  let refs = null;

  function formatError(error) {
    const fieldErrors = error?.problem?.errors;
    if (fieldErrors && typeof fieldErrors === 'object') {
      const details = Object.values(fieldErrors).filter(Boolean).join(' · ');
      if (details) return `${error.message}: ${details}`;
    }
    return error?.message || 'Не удалось связаться с сервером';
  }

  function setError(message = '') {
    refs.error.textContent = message;
  }

  function setBusy(form, busy) {
    [...form.elements].forEach(element => { element.disabled = busy; });
    form.setAttribute('aria-busy', String(busy));
  }

  function selectMode(mode) {
    const register = mode === 'register';
    refs.loginForm.hidden = register;
    refs.registerForm.hidden = !register;
    refs.modeButtons.forEach(button => {
      button.setAttribute('aria-selected', String(button.dataset.authMode === mode));
    });
    setError();
    const target = register ? refs.registerForm : refs.loginForm;
    target.querySelector('input')?.focus();
  }

  function showAuth(error) {
    refs.appShell.hidden = true;
    refs.authView.hidden = false;
    window.QH_APP?.stop();
    if (currentUser && error) {
      setError('Сессия завершилась. Войдите снова — введённый текст сохранён на этой странице.');
    }
    window.setTimeout(() => refs.loginForm.querySelector('input')?.focus(), 0);
  }

  function showApplication(user) {
    if (lastUserId && lastUserId !== user.id) {
      window.location.reload();
      return;
    }
    currentUser = user;
    lastUserId = user.id;
    refs.currentUser.textContent = user.username;
    refs.authView.hidden = true;
    refs.appShell.hidden = false;
    setError();
    window.QH_APP?.start(user);
  }

  async function login(credentials) {
    const user = await window.QH_API.request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials)
    });
    await window.QH_API.refreshCsrf();
    showApplication(user);
    return user;
  }

  async function register(credentials) {
    const user = await window.QH_API.request('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify(credentials)
    });
    await window.QH_API.refreshCsrf();
    showApplication(user);
    return user;
  }

  async function submitLogin(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(form, true);
    setError();
    try {
      await login({ login: data.get('login'), password: data.get('password') });
      form.reset();
    } catch (error) {
      showAuth();
      setError(formatError(error));
    } finally {
      setBusy(form, false);
    }
  }

  async function submitRegistration(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(form, true);
    setError();
    try {
      await register({
        username: data.get('username'),
        email: data.get('email'),
        password: data.get('password')
      });
      form.reset();
    } catch (error) {
      showAuth();
      setError(formatError(error));
    } finally {
      setBusy(form, false);
    }
  }

  async function logout() {
    refs.logoutButton.disabled = true;
    try {
      await window.QH_API.request('/api/auth/logout', { method: 'POST' });
      window.QH_API.resetCsrf();
      currentUser = null;
      refs.currentUser.textContent = '';
      refs.logoutButton.disabled = false;
      selectMode('login');
      showAuth();
    } catch (error) {
      refs.logoutButton.disabled = false;
      if (error.status !== 401) window.alert(formatError(error));
    }
  }

  async function bootstrap() {
    refs = {
      authView: document.querySelector('#auth-view'),
      appShell: document.querySelector('#app-shell'),
      loginForm: document.querySelector('#login-form'),
      registerForm: document.querySelector('#register-form'),
      modeButtons: [...document.querySelectorAll('[data-auth-mode]')],
      error: document.querySelector('#auth-error'),
      currentUser: document.querySelector('#current-user'),
      logoutButton: document.querySelector('#logout-button')
    };

    refs.modeButtons.forEach(button => button.addEventListener('click', () => selectMode(button.dataset.authMode)));
    refs.loginForm.addEventListener('submit', submitLogin);
    refs.registerForm.addEventListener('submit', submitRegistration);
    refs.logoutButton.addEventListener('click', logout);
    window.QH_API.onUnauthorized(showAuth);

    try {
      showApplication(await window.QH_API.request('/api/auth/me'));
    } catch (error) {
      showAuth();
      if (error.status !== 401) setError(formatError(error));
    }
  }

  window.QH_AUTH = Object.freeze({
    bootstrap,
    login,
    register,
    logout,
    get currentUser() { return currentUser; }
  });

  document.addEventListener('DOMContentLoaded', () => window.QH_AUTH.bootstrap(), { once: true });
})();
