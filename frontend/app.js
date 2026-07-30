(() => {
  'use strict';

  const categories = window.QH_CATEGORIES || [];
  const scenarios = window.QH_SCENARIOS || [];
  const categoryMap = new Map(categories.map(item => [item.id, item]));
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const API_BASE = location.protocol === 'file:' || (location.hostname === 'localhost' && ['8090', '5500'].includes(location.port))
    ? 'http://localhost:8080/api'
    : '/api';

  let currentTheoryId = categories[0]?.id;
  let trainerCard = null;
  let trainerOptions = [];
  let trainerAnswered = false;
  let generatedScenarios = [];
  let chatInitialized = false;
  let currentSessionId = null;
  let sending = false;

  const progress = loadProgress();

  function loadProgress() {
    const fallback = { score: 0, streak: 0, answered: 0, correct: 0, seen: [] };
    try {
      const saved = JSON.parse(localStorage.getItem('qh-progress'));
      return { ...fallback, ...(saved || {}), seen: Array.isArray(saved?.seen) ? saved.seen : [] };
    } catch {
      return fallback;
    }
  }

  function saveProgress() {
    try { localStorage.setItem('qh-progress', JSON.stringify(progress)); } catch { /* storage may be disabled */ }
  }

  function shuffle(values) {
    const copy = [...values];
    for (let i = copy.length - 1; i > 0; i -= 1) {
      const j = Math.floor(Math.random() * (i + 1));
      [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
  }

  function escapeHtml(value = '') {
    return String(value).replace(/[&<>'"]/g, char => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
    })[char]);
  }

  function setRoute(route, pushHash = true) {
    const safeRoute = ['theory', 'trainer', 'coach'].includes(route) ? route : 'theory';
    $$('.view').forEach(view => view.classList.toggle('is-active', view.dataset.view === safeRoute));
    $$('.nav-link').forEach(link => link.classList.toggle('is-active', link.dataset.route === safeRoute));
    if (pushHash && location.hash !== `#${safeRoute}`) history.pushState(null, '', `#${safeRoute}`);
    window.scrollTo({ top: 0, behavior: 'smooth' });
    if (safeRoute === 'trainer' && !trainerCard) nextTrainerCard();
    if (safeRoute === 'coach') initChat();
  }

  function bindNavigation() {
    $$('[data-route]').forEach(element => element.addEventListener('click', event => {
      event.preventDefault();
      setRoute(element.dataset.route);
    }));
    $('[data-scroll="theory-grid"]')?.addEventListener('click', () => $('#theory-grid')?.scrollIntoView({ behavior: 'smooth' }));
    window.addEventListener('popstate', () => setRoute(location.hash.slice(1), false));
  }

  function renderTheoryRail() {
    const rail = $('#category-rail');
    rail.innerHTML = categories.map(category => `
      <button class="category-tab ${category.id === currentTheoryId ? 'is-active' : ''}" role="tab"
              aria-selected="${category.id === currentTheoryId}" data-category="${category.id}">
        <span class="num">${category.number}</span>
        <strong>${escapeHtml(category.name)}</strong>
        <span class="arrow">↗</span>
      </button>`).join('');
    $$('.category-tab', rail).forEach(button => button.addEventListener('click', () => {
      currentTheoryId = button.dataset.category;
      renderTheoryRail();
      renderTheoryDetail();
    }));
  }

  function renderTheoryDetail() {
    const category = categoryMap.get(currentTheoryId) || categories[0];
    if (!category) return;
    $('#theory-detail').innerHTML = `
      <div class="detail-topline">
        <span class="detail-number">${category.number}</span>
        <span class="detail-signal">${escapeHtml(category.when)}</span>
      </div>
      <h2>${escapeHtml(category.name)}</h2>
      <h3 class="nickname">«${escapeHtml(category.nickname)}»</h3>
      <p class="detail-definition">${escapeHtml(category.definition)}</p>
      <div class="detail-columns">
        <div class="detail-box"><h4>Как работает</h4><p>${escapeHtml(category.mechanism)}</p></div>
        <div class="detail-box"><h4>Формула</h4><ol class="formula-list">${category.formula.map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ol></div>
      </div>
      <div class="example-table">
        ${category.examples.map(([label, question]) => `<div class="example-row"><span>${escapeHtml(label)}</span><strong>«${escapeHtml(question)}»</strong></div>`).join('')}
      </div>
      <div class="warning-box"><b>!</b><p><strong>Анти-паттерн.</strong> ${escapeHtml(category.mistake)}</p></div>
      <div class="cue-line">${escapeHtml(category.cue)}</div>`;
  }

  function allScenarios() {
    return [...scenarios, ...generatedScenarios];
  }

  function nextTrainerCard() {
    const pool = allScenarios();
    let available = pool.filter(item => !progress.seen.includes(item.id));
    if (!available.length) {
      progress.seen = [];
      available = pool;
      saveProgress();
      showToast('Круг завершён — карточки перемешаны заново');
    }
    trainerCard = shuffle(available)[0];
    trainerAnswered = false;
    trainerOptions = makeOptions(trainerCard.category);
    renderTrainerCard();
  }

  function makeOptions(correctId) {
    const wrong = shuffle(categories.filter(item => item.id !== correctId)).slice(0, 3);
    return shuffle([categoryMap.get(correctId), ...wrong]);
  }

  function renderTrainerCard() {
    if (!trainerCard) return;
    const card = $('#flip-card');
    card.classList.remove('is-flipped');
    $('#scenario-domain').textContent = trainerCard.domain || 'СИТУАЦИЯ';
    $('#scenario-text').textContent = trainerCard.situation;
    $('#scenario-question').textContent = trainerCard.question;
    $('#answer-grid').innerHTML = trainerOptions.map((category, index) => `
      <button class="answer-option" data-category="${category.id}" data-key="${index + 1}">${escapeHtml(category.name)}</button>`).join('');
    $$('.answer-option', $('#answer-grid')).forEach(button => button.addEventListener('click', () => answerTrainer(button.dataset.category)));
    updateScoreboard();
  }

  function answerTrainer(selectedId) {
    if (trainerAnswered) return;
    trainerAnswered = true;
    const isCorrect = selectedId === trainerCard.category;
    progress.answered += 1;
    if (isCorrect) {
      progress.correct += 1;
      progress.streak += 1;
      progress.score += 10 + Math.min(10, Math.max(0, progress.streak - 1) * 2);
    } else {
      progress.streak = 0;
    }
    if (!progress.seen.includes(trainerCard.id)) progress.seen.push(trainerCard.id);
    saveProgress();

    const category = categoryMap.get(trainerCard.category);
    $('#result-mark').textContent = isCorrect ? `Верно · +${10 + Math.min(10, Math.max(0, progress.streak - 1) * 2)}` : `Не совсем · ${category.name}`;
    $('#result-mark').classList.toggle('wrong', !isCorrect);
    $('#answer-number').textContent = category.number;
    $('#answer-title').textContent = category.name;
    $('#answer-tagline').textContent = `«${category.nickname}»`;
    $('#answer-explanation').innerHTML = `<p>${escapeHtml(trainerCard.explanation)}</p><p><strong>Сигнал:</strong> ${escapeHtml(category.signal)}</p>`;
    updateScoreboard();
    setTimeout(() => $('#flip-card').classList.add('is-flipped'), 180);
  }

  function updateScoreboard() {
    $('#score-value').textContent = progress.score;
    $('#streak-value').textContent = progress.streak;
    $('#accuracy-value').textContent = progress.answered ? `${Math.round(progress.correct / progress.answered * 100)}%` : '—';
    const total = allScenarios().length || 1;
    const inCycle = Math.min(progress.seen.length, total);
    $('#card-counter').textContent = `Карточка ${Math.min(inCycle + (trainerAnswered ? 0 : 1), total)} из ${total}`;
    $('#progress-fill').style.width = `${inCycle / total * 100}%`;
  }

  function bindTrainer() {
    $('#next-card')?.addEventListener('click', nextTrainerCard);
    $('#open-theory')?.addEventListener('click', () => {
      currentTheoryId = trainerCard.category;
      renderTheoryRail();
      renderTheoryDetail();
      setRoute('theory');
      setTimeout(() => $('#theory-grid')?.scrollIntoView({ behavior: 'smooth' }), 80);
    });
    $('#reset-progress')?.addEventListener('click', () => {
      Object.assign(progress, { score: 0, streak: 0, answered: 0, correct: 0, seen: [] });
      saveProgress();
      nextTrainerCard();
      showToast('Прогресс сброшен');
    });
    window.addEventListener('keydown', event => {
      if (!$('#view-trainer').classList.contains('is-active') || trainerAnswered) return;
      const index = Number(event.key) - 1;
      if (index >= 0 && index < trainerOptions.length) answerTrainer(trainerOptions[index].id);
    });
  }

  async function api(path, options = {}) {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
      ...options
    });
    if (!response.ok) {
      let message = `${response.status} ${response.statusText}`;
      try { message = (await response.json()).message || message; } catch { /* empty */ }
      throw new Error(message);
    }
    return response.status === 204 ? null : response.json();
  }

  async function initChat() {
    if (chatInitialized) return;
    chatInitialized = true;
    bindChatControls();
    await Promise.allSettled([loadSystemStatus(), loadSessions()]);
  }

  async function loadSystemStatus() {
    try {
      const status = await api('/system/status');
      const online = status.acpEnabled;
      $('#connection-dot').classList.toggle('online', online);
      $('#connection-dot').classList.toggle('fallback', !online && status.fallbackEnabled);
      $('#connection-label').textContent = online ? 'ACP backend подключён' : 'локальный fallback';
      $('#agent-dot').classList.toggle('online', online);
      $('#agent-dot').classList.toggle('fallback', !online && status.fallbackEnabled);
      $('#agent-status').textContent = online
        ? 'Java-клиент готов запускать ACP-совместимого агента.'
        : 'ACP выключен; ответы даст встроенный методический fallback.';
      $('#agent-command').textContent = status.agentCommand;
    } catch (error) {
      $('#connection-label').textContent = 'backend недоступен';
      $('#agent-status').textContent = 'Теория и карточки работают офлайн. Для чата запустите backend.';
      $('#agent-command').textContent = 'java backend → :8080';
    }
  }

  async function loadSessions(preferredId = null) {
    try {
      let sessions = await api('/chat/sessions');
      if (!sessions.length) {
        const created = await api('/chat/sessions', { method: 'POST', body: JSON.stringify({ title: 'Новый диалог' }) });
        sessions = [created];
      }
      renderSessions(sessions);
      const target = preferredId || currentSessionId || sessions[0].id;
      await selectSession(target);
    } catch (error) {
      renderOfflineChat(error.message);
    }
  }

  function renderSessions(sessions) {
    const list = $('#session-list');
    list.innerHTML = sessions.map(session => `
      <button class="session-item ${session.id === currentSessionId ? 'is-active' : ''}" data-session="${session.id}">
        <strong>${escapeHtml(session.title)}</strong>
        <small>${formatDate(session.updatedAt)}</small>
      </button>`).join('');
    $$('.session-item', list).forEach(button => button.addEventListener('click', () => selectSession(button.dataset.session)));
  }

  async function selectSession(sessionId) {
    if (!sessionId) return;
    currentSessionId = sessionId;
    $$('.session-item').forEach(item => item.classList.toggle('is-active', item.dataset.session === sessionId));
    const active = $(`.session-item[data-session="${sessionId}"]`);
    $('#chat-title').textContent = active?.querySelector('strong')?.textContent || 'Тренер вопросов';
    try {
      const messages = await api(`/chat/sessions/${sessionId}/messages`);
      renderMessages(messages);
    } catch (error) {
      showToast(error.message);
    }
  }

  function renderMessages(messages) {
    const feed = $('#message-feed');
    if (!messages.length) {
      feed.innerHTML = welcomeMarkup();
      bindSuggestions();
      return;
    }
    feed.innerHTML = messages.map(messageMarkup).join('');
    feed.scrollTop = feed.scrollHeight;
  }

  function welcomeMarkup() {
    return `
      <article class="welcome-panel" id="chat-welcome">
        <div class="welcome-symbol">?</div>
        <h2>Принесите реальную задачу.<br>Мы взломаем её рамку.</h2>
        <p>Коуч выберет подходящие категории, предложит точные вопросы, покажет анти-паттерн и даст микро-эксперимент на 24–48 часов.</p>
        <div class="suggestion-grid">
          <button data-prompt="Команда месяцами улучшает онбординг, но активация не растёт. Помоги выбрать вопросы-взломщики.">Онбординг не растёт</button>
          <button data-prompt="Мы хотим найти риски перед запуском новой B2B-платформы. Проведи меня через инверсию и premortem.">Найти риски запуска</button>
          <button data-prompt="Продукт перегружен функциями. Как с помощью упрощения и первых принципов найти ядро ценности?">Упростить продукт</button>
          <button data-prompt="Нужна прорывная идея для корпоративного обучения. Используй кросс-дисциплину и гиперболу.">Найти прорывную идею</button>
        </div>
      </article>`;
  }

  function messageMarkup(message) {
    const assistant = message.role === 'ASSISTANT';
    return `<article class="message ${assistant ? 'assistant' : 'user'}">
      <div class="message-meta">${assistant ? 'Тренер' : 'Вы'}<br>${formatTime(message.createdAt)}</div>
      <div class="message-body">${assistant ? renderMarkdown(message.content) : escapeHtml(message.content).replace(/\n/g, '<br>')}
        ${assistant ? `<span class="message-source">${escapeHtml(message.source || 'assistant')}</span>` : ''}
      </div>
    </article>`;
  }

  function appendUserMessage(text) {
    const feed = $('#message-feed');
    $('#chat-welcome')?.remove();
    feed.insertAdjacentHTML('beforeend', `<article class="message user"><div class="message-meta">Вы<br>сейчас</div><div class="message-body">${escapeHtml(text).replace(/\n/g, '<br>')}</div></article>`);
    feed.scrollTop = feed.scrollHeight;
  }

  function appendStreamingAssistant() {
    const feed = $('#message-feed');
    const id = `stream-${Date.now()}`;
    feed.insertAdjacentHTML('beforeend', `<article class="message assistant" id="${id}"><div class="message-meta">Тренер<br>пишет</div><div class="message-body typing-caret"></div></article>`);
    feed.scrollTop = feed.scrollHeight;
    return { id, markdown: '' };
  }

  async function sendMessage(textOverride = null) {
    if (sending) return;
    const input = $('#message-input');
    const text = (textOverride ?? input.value).trim();
    if (!text) return;
    if (!currentSessionId) {
      showToast('Backend недоступен: запустите Java-приложение');
      return;
    }

    sending = true;
    $('#send-message').disabled = true;
    input.value = '';
    resizeComposer();
    appendUserMessage(text);
    const streamMessage = appendStreamingAssistant();

    try {
      const { runId } = await api(`/chat/sessions/${currentSessionId}/messages`, {
        method: 'POST', body: JSON.stringify({ text })
      });
      consumeRun(runId, streamMessage);
    } catch (error) {
      finishStreaming(streamMessage, `## Не удалось отправить сообщение\n\n${error.message}`, 'ERROR');
      sending = false;
      $('#send-message').disabled = false;
    }
  }

  function consumeRun(runId, streamMessage) {
    const source = new EventSource(`${API_BASE}/chat/runs/${runId}/events`);
    source.addEventListener('delta', event => {
      const data = JSON.parse(event.data);
      streamMessage.markdown += data.text || '';
      updateStreaming(streamMessage);
    });
    source.addEventListener('done', event => {
      const data = JSON.parse(event.data);
      source.close();
      finishStreaming(streamMessage, streamMessage.markdown, data.source || 'ACP');
      sending = false;
      $('#send-message').disabled = false;
      loadSessions(currentSessionId).catch(() => {});
    });
    source.addEventListener('failure', event => {
      const data = JSON.parse(event.data);
      source.close();
      finishStreaming(streamMessage, `## Агент недоступен\n\n${data.message || 'Неизвестная ошибка'}`, 'ERROR');
      sending = false;
      $('#send-message').disabled = false;
    });
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) return;
      source.close();
      if (sending) {
        finishStreaming(streamMessage, streamMessage.markdown || '## Поток прерван\n\nПроверьте backend и конфигурацию ACP.', 'INTERRUPTED');
        sending = false;
        $('#send-message').disabled = false;
      }
    };
  }

  function updateStreaming(streamMessage) {
    const body = $(`#${streamMessage.id} .message-body`);
    if (!body) return;
    body.innerHTML = renderMarkdown(streamMessage.markdown);
    body.classList.add('typing-caret');
    const feed = $('#message-feed');
    feed.scrollTop = feed.scrollHeight;
  }

  function finishStreaming(streamMessage, markdown, source) {
    const article = $(`#${streamMessage.id}`);
    if (!article) return;
    const body = $('.message-body', article);
    body.classList.remove('typing-caret');
    body.innerHTML = `${renderMarkdown(markdown)}<span class="message-source">${escapeHtml(source)}</span>`;
    $('.message-meta', article).innerHTML = `Тренер<br>${formatTime(new Date().toISOString())}`;
  }

  function bindChatControls() {
    $('#composer').addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
    $('#message-input').addEventListener('input', resizeComposer);
    $('#message-input').addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
      }
    });
    $('#new-session').addEventListener('click', async () => {
      try {
        const session = await api('/chat/sessions', { method: 'POST', body: JSON.stringify({ title: 'Новый диалог' }) });
        await loadSessions(session.id);
      } catch (error) { showToast(error.message); }
    });
    $('#generate-scenarios').addEventListener('click', generateMoreScenarios);
    bindSuggestions();
  }

  function bindSuggestions() {
    $$('[data-prompt]', $('#message-feed')).forEach(button => button.addEventListener('click', () => sendMessage(button.dataset.prompt)));
  }

  async function generateMoreScenarios() {
    const button = $('#generate-scenarios');
    button.disabled = true;
    button.textContent = 'Генерируем…';
    try {
      const rows = await api('/scenarios/generate', { method: 'POST', body: JSON.stringify({ count: 7 }) });
      generatedScenarios.push(...rows.map(normalizeGeneratedScenario));
      updateScoreboard();
      showToast(`Добавлено ситуаций: ${rows.length}`);
    } catch (error) {
      showToast(error.message);
    } finally {
      button.disabled = false;
      button.textContent = '+ 7 ситуаций';
    }
  }

  function normalizeGeneratedScenario(row) {
    const text = row.situation || '';
    const quoteAt = text.indexOf('«');
    return {
      id: `gen-${row.id}`,
      category: row.category,
      domain: 'СГЕНЕРИРОВАНО',
      situation: quoteAt > 0 ? text.slice(0, quoteAt).trim() : 'Дополнительная ситуация от ACP-агента.',
      question: quoteAt > 0 ? text.slice(quoteAt).trim() : text,
      explanation: row.explanation
    };
  }

  function renderOfflineChat() {
    currentSessionId = null;
    $('#session-list').innerHTML = '<div style="padding:14px;color:#999;font-size:12px;line-height:1.5">История появится после запуска backend.</div>';
    $('#message-feed').innerHTML = `<article class="welcome-panel"><div class="welcome-symbol">!</div><h2>Чат пока офлайн.</h2><p>Запустите Java backend — и здесь появятся диалоги, потоковые long-post ответы и генерация новых ситуаций. Теория и карточки уже полностью доступны без сервера.</p></article>`;
  }

  function resizeComposer() {
    const textarea = $('#message-input');
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(190, textarea.scrollHeight)}px`;
  }

  function formatDate(value) {
    if (!value) return '';
    return new Intl.DateTimeFormat('ru', { day: '2-digit', month: 'short' }).format(new Date(value));
  }

  function formatTime(value) {
    if (!value) return '';
    return new Intl.DateTimeFormat('ru', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
  }

  function renderMarkdown(markdown = '') {
    const lines = String(markdown).replace(/\r/g, '').split('\n');
    const html = [];
    let listType = null;
    let inCode = false;
    let codeLines = [];

    const closeList = () => {
      if (listType) html.push(`</${listType}>`);
      listType = null;
    };
    const inline = text => escapeHtml(text)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>');

    for (const rawLine of lines) {
      const line = rawLine.trimEnd();
      if (line.trim().startsWith('```')) {
        closeList();
        if (inCode) {
          html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
          codeLines = [];
        }
        inCode = !inCode;
        continue;
      }
      if (inCode) { codeLines.push(rawLine); continue; }
      if (!line.trim()) { closeList(); continue; }
      let match;
      if ((match = line.match(/^###\s+(.+)/))) { closeList(); html.push(`<h3>${inline(match[1])}</h3>`); continue; }
      if ((match = line.match(/^##\s+(.+)/))) { closeList(); html.push(`<h2>${inline(match[1])}</h2>`); continue; }
      if ((match = line.match(/^#\s+(.+)/))) { closeList(); html.push(`<h2>${inline(match[1])}</h2>`); continue; }
      if ((match = line.match(/^>\s?(.+)/))) { closeList(); html.push(`<blockquote>${inline(match[1])}</blockquote>`); continue; }
      if ((match = line.match(/^[-*]\s+(.+)/))) {
        if (listType !== 'ul') { closeList(); listType = 'ul'; html.push('<ul>'); }
        html.push(`<li>${inline(match[1])}</li>`); continue;
      }
      if ((match = line.match(/^\d+[.)]\s+(.+)/))) {
        if (listType !== 'ol') { closeList(); listType = 'ol'; html.push('<ol>'); }
        html.push(`<li>${inline(match[1])}</li>`); continue;
      }
      closeList();
      if (/^---+$/.test(line.trim())) html.push('<hr>');
      else html.push(`<p>${inline(line)}</p>`);
    }
    closeList();
    if (codeLines.length) html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
    return html.join('');
  }

  let toastTimer;
  function showToast(message) {
    const toast = $('#toast');
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 3000);
  }

  function boot() {
    renderTheoryRail();
    renderTheoryDetail();
    bindNavigation();
    bindTrainer();
    updateScoreboard();
    setRoute(location.hash.slice(1) || 'theory', false);
    if ('serviceWorker' in navigator && location.protocol !== 'file:') {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    }
  }

  boot();
})();
