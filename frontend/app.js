(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const TERMINAL_ATTEMPT_STATUSES = new Set(['PASSED', 'NEEDS_REVISION', 'UNVERIFIED']);
  const FIELD_LABELS = { question: 'Вопрос', answer: 'Ответ', reasoning: 'Рассуждение', solution: 'Решение' };
  const STATUS_LABELS = {
    PENDING_REVIEW: 'На проверке', AUTO_REJECTED: 'Автоотказ',
    REJECTED: 'Отклонено', PUBLISHED: 'Опубликовано'
  };
  const REJECTION_LABELS = {
    WEAK_LEARNING_VALUE: 'Слабая учебная ценность', WRONG_CATEGORY: 'Неверная категория',
    DUPLICATE: 'Дубликат', UNSAFE_CONTENT: 'Небезопасный контент',
    POOR_WRITING: 'Слабая формулировка', OTHER: 'Другая причина'
  };

  let booted = false;
  let currentUser = null;
  let categories = [];
  let currentTheoryCode = null;
  let currentTheory = null;
  let trainerIssuance = null;
  let trainerSelection = null;
  let trainerFeedback = null;
  let selectedModel = null;
  let coachMode = 'practice';
  let currentSessionId = null;
  let sending = false;
  let activeStream = null;
  let practiceAssignment = null;
  let practiceAttempt = null;
  let attemptPoll = null;
  let moderationStatus = 'PENDING_REVIEW';
  let moderationRows = [];
  let selectedCandidate = null;
  let pendingDeleteSession = null;
  let systemStatus = null;
  let systemStatusError = null;

  function escapeHtml(value = '') {
    return String(value).replace(/[&<>'"]/g, char => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
    })[char]);
  }

  async function api(path, options = {}) {
    return window.QH_API.request(`/api${path}`, options);
  }

  function idempotencyKey(prefix) {
    return `${prefix}-${crypto.randomUUID()}`;
  }

  function setBusy(element, busy, label) {
    if (!element) return;
    element.disabled = busy;
    element.setAttribute('aria-busy', String(busy));
    if (label) element.textContent = label;
  }

  function setRoute(rawRoute, pushHash = true) {
    const admin = currentUser?.roles?.includes('ADMIN');
    const allowed = admin ? ['theory', 'trainer', 'coach', 'moderation'] : ['theory', 'trainer', 'coach'];
    const route = allowed.includes(rawRoute) ? rawRoute : 'theory';
    $$('.view').forEach(view => view.classList.toggle('is-active', view.dataset.view === route));
    $$('.nav-link').forEach(link => link.classList.toggle('is-active', link.dataset.route === route));
    if (pushHash && location.hash !== `#${route}`) history.pushState(null, '', `#${route}`);
    window.scrollTo({ top: 0, behavior: 'smooth' });
    if (route === 'trainer' && !trainerIssuance) loadTrainerCard();
    if (route === 'coach' && coachMode === 'chat') initChat();
    if (route === 'moderation' && admin) loadModeration();
  }

  function bindNavigation() {
    $$('[data-route]').forEach(element => element.addEventListener('click', event => {
      event.preventDefault();
      setRoute(element.dataset.route);
    }));
    $('[data-scroll="theory-grid"]')?.addEventListener('click', () => $('#theory-grid')?.scrollIntoView({ behavior: 'smooth' }));
    window.addEventListener('popstate', () => setRoute(location.hash.slice(1), false));
  }

  async function loadCurriculum() {
    try {
      categories = await api('/curriculum/categories');
      currentTheoryCode ||= categories[0]?.code;
      renderTheoryRail();
      if (currentTheoryCode) await loadTheoryDetail(currentTheoryCode);
    } catch (error) {
      $('#theory-detail').innerHTML = errorPanel('Программа недоступна', error.message);
    }
  }

  function renderTheoryRail() {
    const rail = $('#category-rail');
    rail.innerHTML = categories.map(category => `
      <button class="category-tab ${category.code === currentTheoryCode ? 'is-active' : ''}" role="tab"
              aria-selected="${category.code === currentTheoryCode}" data-category="${escapeHtml(category.code)}">
        <span class="num">${escapeHtml(category.number)}</span><strong>${escapeHtml(category.name)}</strong><span class="arrow">↗</span>
      </button>`).join('');
    $$('.category-tab', rail).forEach(button => button.addEventListener('click', async () => {
      currentTheoryCode = button.dataset.category;
      renderTheoryRail();
      await loadTheoryDetail(currentTheoryCode);
    }));
  }

  async function loadTheoryDetail(code) {
    const panel = $('#theory-detail');
    panel.setAttribute('aria-busy', 'true');
    try {
      currentTheory = await api(`/curriculum/categories/${encodeURIComponent(code)}`);
      renderTheoryDetail(currentTheory);
    } catch (error) {
      panel.innerHTML = errorPanel('Глава недоступна', error.message);
    } finally {
      panel.setAttribute('aria-busy', 'false');
    }
  }

  function renderTheoryDetail(category) {
    const evidence = category.sections || [];
    $('#theory-detail').innerHTML = `
      <div class="detail-topline"><span class="detail-number">${escapeHtml(category.number)}</span><span class="detail-signal">${escapeHtml(category.when)}</span></div>
      <h2>${escapeHtml(category.name)}</h2><h3 class="nickname">«${escapeHtml(category.nickname)}»</h3>
      <p class="detail-definition">${escapeHtml(category.definition)}</p>
      <div class="detail-columns"><div class="detail-box"><h4>Операция</h4><p>${escapeHtml(category.operation)}</p></div><div class="detail-box"><h4>Как работает</h4><p>${escapeHtml(category.mechanism)}</p></div></div>
      <div class="detail-box theory-formula"><h4>Формула</h4><ol class="formula-list">${category.formula.map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ol></div>
      <div class="example-table">${category.examples.map(item => `<div class="example-row"><span>${escapeHtml(item[0])}</span><strong>«${escapeHtml(item[1])}»</strong></div>`).join('')}</div>
      <div class="warning-box"><b>!</b><p><strong>Анти-паттерн.</strong> ${escapeHtml(category.mistake)}</p></div>
      <div class="cue-line"><strong>Контрольный сигнал:</strong> ${escapeHtml(category.cue)}</div>
      <section class="theory-evidence"><div class="expansion-head"><span>ДОКАЗАТЕЛЬНЫЙ СЛОЙ</span><strong>${evidence.length} материалов</strong></div>
        ${evidence.map(section => `<article class="evidence-card"><div class="evidence-meta"><span>${escapeHtml(section.evidenceGrade || '—')}</span><h4>${escapeHtml(section.title)}</h4>${section.source ? `<a href="${escapeHtml(section.source.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(section.source.title)} ↗</a>` : ''}</div><p>${escapeHtml(section.content)}</p></article>`).join('')}
      </section>
      ${category.contrasts?.length ? `<section class="contrast-list"><h3>Не перепутать</h3>${category.contrasts.map(item => `<p><strong>${escapeHtml(item.otherName)}:</strong> ${escapeHtml(item.text)}</p>`).join('')}</section>` : ''}`;
  }

  async function loadTrainerCard() {
    const card = $('#trainer-card');
    const difficulty = $('#difficulty-select').value;
    card.setAttribute('aria-busy', 'true');
    $('#trainer-error').textContent = '';
    $('#trainer-feedback').classList.add('is-hidden');
    trainerFeedback = null;
    trainerSelection = null;
    try {
      const suffix = difficulty ? `?difficulty=${encodeURIComponent(difficulty)}` : '';
      trainerIssuance = await api(`/trainer/next${suffix}`);
      renderTrainerCard(trainerIssuance.card);
    } catch (error) {
      trainerIssuance = null;
      $('#scenario-text').textContent = error.message;
      $('#answer-grid').innerHTML = '';
    } finally {
      card.setAttribute('aria-busy', 'false');
    }
  }

  function renderTrainerCard(card) {
    $('#scenario-domain').textContent = `${card.domain} · ${card.difficulty}`;
    $('#scenario-text').textContent = card.situation;
    $('#scenario-question').textContent = card.question;
    $('#trainer-rationale').value = '';
    $('#answer-grid').innerHTML = card.options.map(option => `
      <button class="answer-option" type="button" data-category="${escapeHtml(option.code)}" aria-pressed="false">${escapeHtml(option.name)}</button>`).join('');
    $$('.answer-option', $('#answer-grid')).forEach(button => button.addEventListener('click', () => {
      trainerSelection = button.dataset.category;
      $$('.answer-option', $('#answer-grid')).forEach(item => {
        const active = item === button;
        item.classList.toggle('is-selected', active);
        item.setAttribute('aria-pressed', String(active));
      });
      $('#trainer-error').textContent = '';
    }));
    $('#answer-fieldset').disabled = false;
    $('#trainer-rationale').disabled = false;
    $('#submit-trainer').disabled = false;
    $('#card-counter').textContent = `Срок ответа: ${formatTime(trainerIssuance.expiresAt)}`;
  }

  async function submitTrainer() {
    if (!trainerIssuance) return;
    const rationale = $('#trainer-rationale').value.trim();
    if (!trainerSelection) {
      $('#trainer-error').textContent = 'Выберите категорию.';
      return;
    }
    if (rationale.length < 20) {
      $('#trainer-error').textContent = 'Объясните выбор хотя бы в 20 символах.';
      $('#trainer-rationale').focus();
      return;
    }
    const button = $('#submit-trainer');
    setBusy(button, true, 'Сверяем…');
    try {
      trainerFeedback = await api('/trainer/attempts', {
        method: 'POST', body: JSON.stringify({ issuanceId: trainerIssuance.issuanceId, selectedCategory: trainerSelection, rationale })
      });
      renderTrainerFeedback(trainerFeedback);
      $('#answer-fieldset').disabled = true;
      $('#trainer-rationale').disabled = true;
      await refreshProgressView();
    } catch (error) {
      $('#trainer-error').textContent = error.message;
      setBusy(button, false, 'Проверить на сервере →');
    }
  }

  function renderTrainerFeedback(value) {
    const category = categories.find(item => item.code === value.correctCategory);
    const panel = $('#trainer-feedback');
    panel.classList.remove('is-hidden');
    panel.classList.toggle('passed', value.correct);
    panel.innerHTML = `
      <div class="feedback-verdict">${value.correct ? 'Верно' : 'Нужно различить'} · ${escapeHtml(category?.name || value.correctCategory)}</div>
      <div class="feedback-score">${Math.round(value.mastery.score)}<small>/ 100</small></div>
      <h3>${value.correct ? 'Операция распознана.' : 'Категория определяется операцией вопроса.'}</h3>
      <p>${escapeHtml(value.operationExplanation)}</p>
      <div class="feedback-next"><strong>Контраст</strong><span>${escapeHtml(value.contrast)}</span></div>
      <div class="feedback-next"><strong>Следующий шаг</strong><span>${escapeHtml(value.nextStep)}</span></div>
      <div class="back-actions"><button class="primary-button" id="next-card" type="button">Следующая карточка <span>→</span></button><button class="text-button" id="open-theory" type="button">Открыть теорию</button></div>`;
    $('#next-card').addEventListener('click', loadTrainerCard);
    $('#open-theory').addEventListener('click', () => {
      currentTheoryCode = value.correctCategory;
      renderTheoryRail();
      loadTheoryDetail(currentTheoryCode);
      setRoute('theory');
    });
    panel.focus({ preventScroll: true });
    panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  async function refreshProgressView() {
    try {
      const progress = await api('/progress');
      const attempted = progress.categories.filter(item => item.attempts > 0);
      const attempts = progress.categories.reduce((sum, item) => sum + item.attempts, 0);
      const correct = progress.categories.reduce((sum, item) => sum + item.correctAnswers, 0);
      const mastery = progress.categories.length ? progress.categories.reduce((sum, item) => sum + item.score, 0) / progress.categories.length : 0;
      $('#mastery-value').textContent = `${Math.round(mastery)}%`;
      $('#attempts-value').textContent = String(attempts);
      $('#accuracy-value').textContent = attempts ? `${Math.round(correct * 100 / attempts)}%` : '—';
      $('#progress-fill').style.width = `${Math.max(0, Math.min(100, mastery))}%`;
      $('#progress-recommendation').textContent = progress.recommendation;
      $('#progress-recommendation').dataset.attempted = String(attempted.length);
    } catch (error) {
      $('#progress-recommendation').textContent = error.message;
    }
  }

  function bindTrainer() {
    $('#submit-trainer').addEventListener('click', submitTrainer);
    $('#refresh-progress').addEventListener('click', refreshProgressView);
    $('#difficulty-select').addEventListener('change', loadTrainerCard);
  }

  async function loadSystemStatus() {
    try {
      const status = await api('/system/status');
      systemStatus = status;
      systemStatusError = null;
      const online = status.acpAvailable;
      $('#connection-dot').classList.toggle('online', online);
      $('#connection-dot').classList.toggle('fallback', !online && status.fallbackEnabled);
      $('#connection-label').textContent = online ? 'ACP подключён' : 'серверный fallback';
      $('#agent-dot').classList.toggle('online', online);
      $('#agent-dot').classList.toggle('fallback', !online && status.fallbackEnabled);
      $('#agent-status').textContent = online ? 'Семантическая оценка доступна.' : 'Ответы коуча работают через fallback; оценка может стать непроверенной.';
      $('#agent-command').textContent = status.agentCommand || '—';
      const models = (status.models || []).slice(0, 3);
      selectedModel = models.includes(status.defaultModel) ? status.defaultModel : models[0] || null;
      $('#model-select').innerHTML = models.map(model => `<option value="${escapeHtml(model)}">${escapeHtml(model)}</option>`).join('');
      $('#model-select').value = selectedModel || '';
      $('#model-select').disabled = !models.length;
    } catch (error) {
      systemStatus = null;
      systemStatusError = error.message;
      $('#connection-dot').classList.remove('online', 'fallback');
      $('#agent-dot').classList.remove('online', 'fallback');
      $('#connection-label').textContent = 'сервер недоступен';
      $('#agent-status').textContent = error.message;
      $('#agent-command').textContent = '—';
    }
  }

  function bindAcpStatus() {
    $('#agent-status-card')?.addEventListener('click', openAcpStatusDialog);
  }

  function openAcpStatusDialog() {
    const dialog = $('#acp-status-dialog');
    const available = systemStatus?.acpAvailable === true;
    const fallback = !available && systemStatus?.fallbackEnabled === true;
    $('#acp-dialog-state').textContent = available
      ? 'ACP-сессии доступны'
      : fallback ? 'Работает серверный fallback' : 'ACP-сессии недоступны';
    $('#acp-dialog-reason').textContent = available
      ? 'Последний запуск ACP завершился успешно; новых ошибок не зафиксировано.'
      : systemStatus?.acpReason || systemStatusError || 'ACP-сессия недоступна; подробная причина не получена.';
    $('#acp-dialog-command').textContent = systemStatus?.agentCommand || '—';
    dialog.showModal();
  }

  function setCoachMode(mode) {
    coachMode = mode === 'chat' ? 'chat' : 'practice';
    $$('[data-coach-mode]').forEach(button => button.classList.toggle('is-active', button.dataset.coachMode === coachMode));
    $('#practice-panel').classList.toggle('is-hidden', coachMode !== 'practice');
    $('#new-practice').classList.toggle('is-hidden', coachMode !== 'practice');
    $('#message-feed').classList.toggle('is-hidden', coachMode !== 'chat');
    $('#composer').classList.toggle('is-hidden', coachMode !== 'chat');
    $('#session-tools').classList.toggle('is-hidden', coachMode !== 'chat');
    $('#chat-title').textContent = coachMode === 'practice' ? 'Практика полного цикла' : 'Тренер вопросов';
    if (coachMode === 'chat') initChat();
  }

  async function startPractice() {
    clearAttemptPoll();
    const buttons = [$('#start-practice'), $('#new-practice')];
    buttons.forEach(button => setBusy(button, true));
    try {
      practiceAssignment = await api('/practice/assignments', { method: 'POST', body: '{}' });
      practiceAttempt = null;
      $('#practice-empty').classList.add('is-hidden');
      $('#practice-workspace').classList.remove('is-hidden');
      $('#practice-domain').textContent = `${practiceAssignment.domain} · ${practiceAssignment.targetCategory.name}`;
      $('#practice-situation').textContent = practiceAssignment.situation;
      $('#practice-guidance').textContent = practiceAssignment.targetCategory.guidance;
      $('#practice-form').reset();
      setRevisionFields([]);
      $('#practice-attempt').textContent = 'Попытка 1';
      $('#practice-feedback').classList.add('is-hidden');
      $('#practice-error').textContent = '';
      updatePracticeProgress();
      $('#practice-question').focus();
    } catch (error) {
      showToast(error.message);
    } finally {
      buttons.forEach(button => setBusy(button, false));
      $('#new-practice').textContent = 'Новая ситуация';
      $('#start-practice').innerHTML = 'Получить ситуацию <span>→</span>';
    }
  }

  function practiceValues() {
    return Object.fromEntries(Object.keys(FIELD_LABELS).map(field => [field, $(`#practice-${field}`).value.trim()]));
  }

  function validatePractice(values, revisionFields = null) {
    const minimums = { question: 30, answer: 40, reasoning: 50, solution: 35 };
    const fields = revisionFields?.length ? revisionFields : Object.keys(FIELD_LABELS);
    for (const field of fields) {
      if (values[field].length < minimums[field]) return `${FIELD_LABELS[field]}: нужно не менее ${minimums[field]} содержательных символов.`;
    }
    return '';
  }

  async function submitPractice(event) {
    event.preventDefault();
    if (!practiceAssignment) return;
    const values = practiceValues();
    const fieldsToRevise = practiceAttempt?.assessment?.fieldsToRevise || [];
    const error = validatePractice(values, practiceAttempt?.status === 'NEEDS_REVISION' ? fieldsToRevise : null);
    if (error) {
      $('#practice-error').textContent = error;
      return;
    }
    $('#practice-error').textContent = '';
    const revision = practiceAttempt?.status === 'NEEDS_REVISION';
    const path = revision ? `/practice/attempts/${practiceAttempt.attemptId}/revisions` : '/practice/attempts';
    const body = revision
      ? Object.fromEntries([...fieldsToRevise.map(field => [field, values[field]]), ['model', selectedModel], ['idempotencyKey', idempotencyKey('revision')]])
      : { assignmentId: practiceAssignment.assignmentId, ...values, model: selectedModel, idempotencyKey: idempotencyKey('attempt') };
    setBusy($('#submit-practice'), true, 'Оцениваем…');
    updatePracticeProgress(true);
    try {
      practiceAttempt = await api(path, { method: 'POST', body: JSON.stringify(body) });
      $('#practice-attempt').textContent = `Попытка ${practiceAttempt.attemptNumber} · оценка сервера`;
      await followAttempt(practiceAttempt.attemptId);
    } catch (requestError) {
      $('#practice-error').textContent = requestError.message;
      setBusy($('#submit-practice'), false, 'Отправить на оценку →');
    }
  }

  async function followAttempt(attemptId) {
    clearAttemptPoll();
    const poll = async () => {
      try {
        practiceAttempt = await api(`/practice/attempts/${attemptId}`);
        if (TERMINAL_ATTEMPT_STATUSES.has(practiceAttempt.status)) {
          clearAttemptPoll();
          renderPracticeFeedback(practiceAttempt);
          return;
        }
        attemptPoll = window.setTimeout(poll, 900);
      } catch (error) {
        clearAttemptPoll();
        $('#practice-error').textContent = error.message;
        setBusy($('#submit-practice'), false, 'Повторить →');
      }
    };
    await poll();
  }

  function clearAttemptPoll() {
    if (attemptPoll) window.clearTimeout(attemptPoll);
    attemptPoll = null;
  }

  function renderPracticeFeedback(attempt) {
    const assessment = attempt.assessment;
    const passed = attempt.status === 'PASSED';
    const unverified = attempt.status === 'UNVERIFIED';
    const panel = $('#practice-feedback');
    panel.classList.remove('is-hidden', 'passed');
    panel.classList.toggle('passed', passed);
    const fit = assessment.categoryFitScore == null ? '—' : `${assessment.categoryFitScore}/2`;
    const strength = assessment.questionStrengthScore == null ? '—' : `${assessment.questionStrengthScore}/5`;
    panel.innerHTML = `
      <div class="feedback-verdict">${passed ? 'Зачёт' : unverified ? 'Не проверено' : 'Нужна корректировка'} · ${escapeHtml(attempt.targetCategory.name)}</div>
      <h3>${passed ? 'Полный ход мысли принят.' : unverified ? 'Сервер не стал выдумывать оценку.' : 'Исправьте отмеченные шаги.'}</h3>
      <div class="assessment-grid">
        <section><span>Полнота</span><strong>${escapeHtml(assessment.completeness || '—')}</strong><small>${assessment.steps.map(step => `${FIELD_LABELS[step.field] || step.field}: ${step.status}`).join(' · ')}</small></section>
        <section><span>Категория</span><strong>${fit}</strong><small>${escapeHtml(assessment.categoryFitEvidence || 'Семантический балл не присвоен')}</small></section>
        <section><span>Сила вопроса</span><strong>${strength}</strong><small>${escapeHtml(assessment.confidence ? `Уверенность: ${assessment.confidence}` : 'Семантический балл не присвоен')}</small></section>
      </div>
      <p>${escapeHtml(assessment.feedback)}</p>
      ${assessment.strengths?.length ? `<div class="feedback-next"><strong>Что уже хорошо</strong><span>${assessment.strengths.map(escapeHtml).join(' · ')}</span></div>` : ''}
      ${!passed && !unverified ? `<div class="feedback-next"><strong>Приоритетная правка</strong><span>${escapeHtml(assessment.priorityCorrection.what)} — ${escapeHtml(assessment.priorityCorrection.why)}<br><em>${escapeHtml(assessment.priorityCorrection.example)}</em></span></div>` : ''}
      <div class="back-actions">${passed ? '<button class="primary-button" id="practice-next" type="button">Следующая ситуация <span>→</span></button>' : unverified ? '<button class="secondary-button" id="practice-retry" type="button">Проверить позже</button>' : '<button class="primary-button" id="practice-revise" type="button">Исправить отмеченное <span>→</span></button>'}</div>`;
    setBusy($('#submit-practice'), false, passed ? 'Зачтено' : 'Отправить исправление →');
    $('#submit-practice').disabled = passed || unverified;
    if (passed) $('#practice-next').addEventListener('click', startPractice);
    if (unverified) $('#practice-retry').addEventListener('click', () => showToast('Попытка сохранена. Создайте новую проверку, когда модель будет доступна.'));
    if (!passed && !unverified) {
      setRevisionFields(assessment.fieldsToRevise);
      $('#practice-revise').addEventListener('click', () => focusFirstRevision(assessment.fieldsToRevise));
    }
    updatePracticeProgress(false, passed);
    panel.focus({ preventScroll: true });
    panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function setRevisionFields(fields) {
    const revision = fields.length > 0;
    Object.keys(FIELD_LABELS).forEach(field => {
      const input = $(`#practice-${field}`);
      const editable = !revision || fields.includes(field);
      input.disabled = !editable;
      input.closest('.practice-form')?.classList.toggle('is-revision', revision);
      input.classList.toggle('needs-revision', revision && editable);
    });
  }

  function focusFirstRevision(fields) {
    if (fields[0]) $(`#practice-${fields[0]}`)?.focus();
  }

  function updatePracticeProgress(evaluating = false, passed = false) {
    const values = practiceValues();
    $$('.practice-progress span').forEach((item, index) => {
      const field = Object.keys(FIELD_LABELS)[index];
      item.classList.toggle('is-active', passed || evaluating || values[field]?.length > 0);
    });
  }

  function bindPractice() {
    $$('[data-coach-mode]').forEach(button => button.addEventListener('click', () => setCoachMode(button.dataset.coachMode)));
    $('#start-practice').addEventListener('click', startPractice);
    $('#new-practice').addEventListener('click', startPractice);
    $('#practice-form').addEventListener('submit', submitPractice);
    $('#practice-form').addEventListener('input', () => updatePracticeProgress());
    $('#model-select').addEventListener('change', event => { selectedModel = event.target.value || null; });
  }

  async function initChat() {
    if (currentSessionId) return;
    try {
      let sessions = await api('/chat/sessions');
      if (!sessions.length) sessions = [await api('/chat/sessions', { method: 'POST', body: JSON.stringify({ title: 'Новый диалог' }) })];
      renderSessions(sessions);
      await selectSession(sessions[0].id);
    } catch (error) {
      $('#message-feed').innerHTML = errorPanel('Диалог недоступен', error.message);
    }
  }

  function renderSessions(sessions) {
    $('#session-list').innerHTML = sessions.map(session => `<div class="session-row ${session.id === currentSessionId ? 'is-active' : ''}"><button class="session-item" data-session="${session.id}"><strong>${escapeHtml(session.title)}</strong><small>${formatDate(session.updatedAt)}</small></button><button class="session-delete" data-delete-session="${session.id}" data-title="${escapeHtml(session.title)}" aria-label="Удалить диалог">×</button></div>`).join('');
    $$('.session-item').forEach(button => button.addEventListener('click', () => selectSession(button.dataset.session)));
    $$('.session-delete').forEach(button => button.addEventListener('click', () => requestDeleteSession(button.dataset.deleteSession, button.dataset.title)));
  }

  async function reloadSessions(preferredId) {
    const sessions = await api('/chat/sessions');
    renderSessions(sessions);
    if (preferredId && sessions.some(item => item.id === preferredId)) await selectSession(preferredId);
  }

  async function selectSession(id) {
    currentSessionId = id;
    $$('.session-row').forEach(row => row.classList.toggle('is-active', row.querySelector('.session-item')?.dataset.session === id));
    const messages = await api(`/chat/sessions/${id}/messages`);
    renderMessages(messages);
  }

  function renderMessages(messages) {
    const feed = $('#message-feed');
    feed.innerHTML = messages.length ? messages.map(messageMarkup).join('') : `<article class="welcome-panel"><div class="welcome-symbol">?</div><h2>Принесите реальную задачу.</h2><p>Коуч поможет выбрать технику, сформулировать вопрос и спроектировать эксперимент.</p></article>`;
    feed.scrollTop = feed.scrollHeight;
  }

  function messageMarkup(message) {
    const assistant = message.role === 'ASSISTANT';
    return `<article class="message ${assistant ? 'assistant' : 'user'}"><div class="message-meta">${assistant ? 'Тренер' : 'Вы'}<br>${formatTime(message.createdAt)}</div><div class="message-body">${assistant ? renderMarkdown(message.content) : escapeHtml(message.content).replace(/\n/g, '<br>')}${assistant ? `<span class="message-source">${escapeHtml(message.source || 'assistant')}</span>` : ''}</div></article>`;
  }

  async function sendMessage() {
    if (sending || !currentSessionId) return;
    const input = $('#message-input');
    const text = input.value.trim();
    if (!text) return;
    sending = true;
    $('#send-message').disabled = true;
    input.value = '';
    $('#message-feed').insertAdjacentHTML('beforeend', messageMarkup({ role: 'USER', content: text, createdAt: new Date().toISOString() }));
    const streamId = `stream-${Date.now()}`;
    $('#message-feed').insertAdjacentHTML('beforeend', `<article class="message assistant" id="${streamId}"><div class="message-meta">Тренер<br>пишет</div><div class="message-body typing-caret"></div></article>`);
    try {
      const run = await api(`/chat/sessions/${currentSessionId}/messages`, { method: 'POST', body: JSON.stringify({ text, model: selectedModel }) });
      consumeChatRun(run.runId, streamId);
    } catch (error) {
      finishChat(streamId, `## Ошибка\n\n${error.message}`, 'ERROR');
    }
  }

  function consumeChatRun(runId, streamId) {
    const source = new EventSource(`/api/chat/runs/${runId}/events`);
    activeStream = source;
    let markdown = '';
    source.addEventListener('delta', event => {
      markdown += JSON.parse(event.data).text || '';
      $(`#${streamId} .message-body`).innerHTML = renderMarkdown(markdown);
    });
    source.addEventListener('done', event => {
      source.close(); activeStream = null;
      finishChat(streamId, markdown, JSON.parse(event.data).source || 'ACP');
      reloadSessions(currentSessionId).catch(() => {});
    });
    source.addEventListener('failure', event => {
      source.close(); activeStream = null;
      finishChat(streamId, `## Агент недоступен\n\n${JSON.parse(event.data).message || 'Неизвестная ошибка'}`, 'ERROR');
    });
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) return;
      source.close(); activeStream = null;
      finishChat(streamId, markdown || '## Поток прерван', 'INTERRUPTED');
    };
  }

  function finishChat(streamId, markdown, source) {
    const body = $(`#${streamId} .message-body`);
    if (body) body.innerHTML = `${renderMarkdown(markdown)}<span class="message-source">${escapeHtml(source)}</span>`;
    sending = false;
    $('#send-message').disabled = false;
  }

  function requestDeleteSession(id, title) {
    pendingDeleteSession = { id, title };
    $('#delete-dialog-name').textContent = `«${title}»`;
    $('#delete-session-dialog').showModal();
  }

  async function confirmDeleteSession(event) {
    event.preventDefault();
    if (!pendingDeleteSession) return;
    try {
      await api(`/chat/sessions/${pendingDeleteSession.id}`, { method: 'DELETE' });
      if (currentSessionId === pendingDeleteSession.id) currentSessionId = null;
      $('#delete-session-dialog').close();
      pendingDeleteSession = null;
      await initChat();
    } catch (error) { showToast(error.message); }
  }

  function bindChat() {
    $('#composer').addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
    $('#message-input').addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendMessage(); }
    });
    $('#new-session').addEventListener('click', async () => {
      try {
        const session = await api('/chat/sessions', { method: 'POST', body: JSON.stringify({ title: 'Новый диалог' }) });
        await reloadSessions(session.id);
      } catch (error) { showToast(error.message); }
    });
    $('#delete-session-form').addEventListener('submit', confirmDeleteSession);
    $('#delete-session-cancel').addEventListener('click', () => { pendingDeleteSession = null; $('#delete-session-dialog').close(); });
  }

  async function loadModeration() {
    if (!currentUser?.roles?.includes('ADMIN')) return;
    $('#moderation-status').textContent = 'Загружаем очередь…';
    try {
      moderationRows = await api(`/admin/scenario-candidates?status=${encodeURIComponent(moderationStatus)}`);
      renderCandidateList();
      $('#moderation-status').textContent = `${STATUS_LABELS[moderationStatus]}: ${moderationRows.length}`;
      if (selectedCandidate) {
        selectedCandidate = moderationRows.find(item => item.id === selectedCandidate.id) || null;
        renderCandidateDetail();
      }
    } catch (error) {
      $('#moderation-status').textContent = error.message;
    }
  }

  function renderCandidateList() {
    $('#candidate-list').innerHTML = moderationRows.length ? moderationRows.map(item => `
      <button class="candidate-row ${selectedCandidate?.id === item.id ? 'is-active' : ''}" data-candidate="${item.id}">
        <span>${escapeHtml(item.difficulty || '—')} · ${escapeHtml(item.category || 'без категории')}</span>
        <strong>${escapeHtml(item.situation || 'Некорректный кандидат')}</strong>
        <small>${escapeHtml(item.domain || STATUS_LABELS[item.status] || item.status)}</small>
      </button>`).join('') : '<p class="empty-queue">В этом статусе кейсов нет.</p>';
    $$('.candidate-row').forEach(button => button.addEventListener('click', () => {
      selectedCandidate = moderationRows.find(item => item.id === button.dataset.candidate);
      renderCandidateList();
      renderCandidateDetail();
    }));
  }

  function renderCandidateDetail() {
    const panel = $('#candidate-detail');
    if (!selectedCandidate) { panel.innerHTML = '<p>Выберите кандидат в очереди.</p>'; return; }
    const item = selectedCandidate;
    const editable = item.status === 'PENDING_REVIEW';
    panel.innerHTML = `
      <form id="candidate-form" class="candidate-form">
        <div class="candidate-heading"><span>${escapeHtml(STATUS_LABELS[item.status] || item.status)} · v${item.version}</span><strong>${escapeHtml(item.sourceModel || 'модель не указана')}</strong></div>
        ${item.rejectionReasons?.length ? `<div class="moderation-reasons"><strong>Автоматические причины</strong>${item.rejectionReasons.map(reason => `<span>${escapeHtml(reason)}</span>`).join('')}</div>` : ''}
        <div class="candidate-grid"><label>Категория<input name="category" value="${escapeHtml(item.category || '')}" ${editable ? '' : 'disabled'}></label><label>Сложность<select name="difficulty" ${editable ? '' : 'disabled'}>${['L1','L2','L3'].map(level => `<option ${item.difficulty === level ? 'selected' : ''}>${level}</option>`).join('')}</select></label><label>Домен<input name="domain" value="${escapeHtml(item.domain || '')}" ${editable ? '' : 'disabled'}></label></div>
        <label>Ситуация<textarea name="situation" rows="5" ${editable ? '' : 'disabled'}>${escapeHtml(item.situation || '')}</textarea></label>
        <label>Вопрос<textarea name="question" rows="3" ${editable ? '' : 'disabled'}>${escapeHtml(item.question || '')}</textarea></label>
        <label>Подсказка<textarea name="hint" rows="2" ${editable ? '' : 'disabled'}>${escapeHtml(item.hint || '')}</textarea></label>
        <label>Объяснение<textarea name="explanation" rows="3" ${editable ? '' : 'disabled'}>${escapeHtml(item.explanation || '')}</textarea></label>
        <label>Варианты (коды через запятую)<input name="options" value="${escapeHtml((item.options || []).join(', '))}" ${editable ? '' : 'disabled'}></label>
        <div class="candidate-grid"><label>Правильная<input name="correctCategory" value="${escapeHtml(item.correctCategory || '')}" ${editable ? '' : 'disabled'}></label><label>Путают с<input name="confusedWith" value="${escapeHtml(item.confusedWith || '')}" ${editable ? '' : 'disabled'}></label></div>
        <label>Контраст<textarea name="contrast" rows="2" ${editable ? '' : 'disabled'}>${escapeHtml(item.contrast || '')}</textarea></label>
        ${editable ? `<div class="candidate-actions"><button class="secondary-button" id="save-candidate" type="submit">Сохранить правки</button><button class="primary-button" id="approve-candidate" type="button">Опубликовать <span>→</span></button><button class="text-button danger" id="reject-candidate" type="button">Отклонить</button></div>` : ''}
      </form>`;
    if (editable) {
      $('#candidate-form').addEventListener('submit', saveCandidate);
      $('#approve-candidate').addEventListener('click', approveCandidate);
      $('#reject-candidate').addEventListener('click', rejectCandidate);
    }
  }

  function candidateDraft() {
    const form = new FormData($('#candidate-form'));
    return {
      category: form.get('category'), secondaryCategory: null, difficulty: form.get('difficulty'),
      domain: form.get('domain'), situation: form.get('situation'), question: form.get('question'),
      hint: form.get('hint'), options: String(form.get('options')).split(',').map(item => item.trim()).filter(Boolean),
      correctCategory: form.get('correctCategory'), explanation: form.get('explanation'),
      confusedWith: form.get('confusedWith') || null, contrast: form.get('contrast') || null
    };
  }

  async function saveCandidate(event) {
    event.preventDefault();
    try {
      selectedCandidate = await api(`/admin/scenario-candidates/${selectedCandidate.id}`, { method: 'PUT', body: JSON.stringify({ expectedVersion: selectedCandidate.version, draft: candidateDraft() }) });
      showToast(selectedCandidate.status === 'AUTO_REJECTED' ? 'Правка сохранена, но кандидат не прошёл автофильтр' : 'Правка сохранена');
      await loadModeration();
    } catch (error) { showToast(error.message); }
  }

  async function approveCandidate() {
    try {
      await api(`/admin/scenario-candidates/${selectedCandidate.id}/approve`, { method: 'POST', body: JSON.stringify({ expectedVersion: selectedCandidate.version }) });
      selectedCandidate = null;
      showToast('Кейс опубликован и доступен серверному тренажёру');
      await loadModeration();
    } catch (error) { showToast(error.message); }
  }

  async function rejectCandidate() {
    const reason = window.prompt('Причина: WEAK_LEARNING_VALUE, WRONG_CATEGORY, DUPLICATE, UNSAFE_CONTENT, POOR_WRITING или OTHER', 'WEAK_LEARNING_VALUE');
    if (!reason) return;
    const comment = window.prompt('Комментарий модератора', '') || '';
    try {
      await api(`/admin/scenario-candidates/${selectedCandidate.id}/reject`, { method: 'POST', body: JSON.stringify({ expectedVersion: selectedCandidate.version, reason: reason.trim().toUpperCase(), comment }) });
      selectedCandidate = null;
      showToast(REJECTION_LABELS[reason.trim().toUpperCase()] || 'Кейс отклонён');
      await loadModeration();
    } catch (error) { showToast(error.message); }
  }

  async function generateCandidates() {
    const count = Number($('#moderation-count').value);
    const button = $('#generate-candidates');
    setBusy(button, true, 'Генерируем…');
    try {
      const rows = await api('/admin/scenario-candidates/generate', { method: 'POST', body: JSON.stringify({ count, model: selectedModel }) });
      const rejected = rows.filter(item => item.status === 'AUTO_REJECTED').length;
      moderationStatus = 'PENDING_REVIEW';
      $$('.moderation-toolbar button').forEach(item => item.classList.toggle('is-active', item.dataset.moderationStatus === moderationStatus));
      showToast(`В очередь: ${rows.length - rejected}; автоотказ: ${rejected}`);
      await loadModeration();
    } catch (error) { showToast(error.message); }
    finally { setBusy(button, false, 'В очередь →'); }
  }

  function bindModeration() {
    $('#generate-candidates').addEventListener('click', generateCandidates);
    $$('.moderation-toolbar button').forEach(button => button.addEventListener('click', () => {
      moderationStatus = button.dataset.moderationStatus;
      selectedCandidate = null;
      $$('.moderation-toolbar button').forEach(item => item.classList.toggle('is-active', item === button));
      loadModeration();
    }));
  }

  function renderMarkdown(markdown = '') {
    return escapeHtml(markdown)
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^##? (.+)$/gm, '<h2>$1</h2>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .split(/\n{2,}/).map(block => /^<h[23]>/.test(block) ? block : `<p>${block.replace(/\n/g, '<br>')}</p>`).join('');
  }

  function errorPanel(title, message) {
    return `<article class="error-panel"><strong>${escapeHtml(title)}</strong><p>${escapeHtml(message)}</p></article>`;
  }

  function formatDate(value) {
    return value ? new Intl.DateTimeFormat('ru', { day: '2-digit', month: 'short' }).format(new Date(value)) : '';
  }

  function formatTime(value) {
    return value ? new Intl.DateTimeFormat('ru', { hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '';
  }

  let toastTimer;
  function showToast(message) {
    const toast = $('#toast');
    toast.textContent = message;
    toast.classList.add('show');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toast.classList.remove('show'), 3200);
  }

  async function boot(user) {
    currentUser = user;
    const admin = user.roles?.includes('ADMIN');
    $('[data-route="moderation"]').hidden = !admin;
    bindNavigation();
    bindTrainer();
    bindPractice();
    bindChat();
    bindModeration();
    bindAcpStatus();
    await Promise.allSettled([loadCurriculum(), refreshProgressView(), loadSystemStatus()]);
    setCoachMode('practice');
    setRoute(location.hash.slice(1) || 'theory', false);
    if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
  }

  window.QH_APP = Object.freeze({
    start(user) {
      if (booted) return;
      booted = true;
      boot(user);
    },
    stop() {
      activeStream?.close();
      activeStream = null;
      clearAttemptPoll();
      sending = false;
    }
  });
})();
