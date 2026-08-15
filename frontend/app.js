(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const TERMINAL_ATTEMPT_STATUSES = new Set(['PASSED', 'NEEDS_REVISION', 'UNVERIFIED']);
  const PRACTICE_CATALOG_EXHAUSTED = 'PRACTICE_CATALOG_EXHAUSTED';
  const PRACTICE_EXHAUSTED_MESSAGE = 'Вы прошли все доступные ситуации. Дождитесь, пока администратор добавит новые.';
  const FIELD_LABELS = { question: 'Вопрос', rationale: 'Обоснование', solution: 'Решение' };
  const STATUS_LABELS = {
    PENDING_REVIEW: 'На проверке', AUTO_REJECTED: 'Автоотказ',
    REJECTED: 'Отклонено', PUBLISHED: 'Опубликовано'
  };
  const TARGET_LABELS = { PRACTICE: 'Практика', TRAINER: 'Тренажёр' };
  const REJECTION_LABELS = {
    WEAK_LEARNING_VALUE: 'Слабая учебная ценность', WRONG_CATEGORY: 'Неверная категория',
    DUPLICATE: 'Дубликат', UNSAFE_CONTENT: 'Небезопасный контент',
    POOR_WRITING: 'Слабая формулировка', OTHER: 'Другая причина'
  };
  const EVIDENCE_LABELS = {
    RESEARCH_SUPPORTED: 'Подтверждено исследованием',
    PRACTITIONER_METHOD: 'Практический метод',
    HEURISTIC: 'Эвристическая рекомендация'
  };

  let booted = false;
  let currentUser = null;
  let categories = [];
  let currentTheoryCode = null;
  let currentTheory = null;
  let trainerIssuance = null;
  let trainerSelection = null;
  let trainerFeedback = null;
  let trainerLoadSequence = 0;
  let selectedModel = null;
  let availableModels = [];
  let currentSessionId = null;
  let chatSessions = [];
  let editingSessionId = null;
  let editingSessionValue = '';
  let renameSubmitting = false;
  let sending = false;
  let activeStream = null;
  let practiceAssignment = null;
  let practiceAttempt = null;
  let attemptPoll = null;
  let practiceCycles = [];
  let practiceInitialized = false;
  let practiceLoadSequence = 0;
  let practiceDraftTimer = null;
  let practiceDraftPromise = null;
  let practiceDraftDirty = false;
  let practiceEditorBaseAttemptId = null;
  let practiceEditableFields = [];
  let practiceSubmitting = false;
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

  function firstCharacters(value, limit = 50) {
    return Array.from(String(value || '')).slice(0, limit).join('');
  }

  function truncateText(value, limit) {
    const characters = Array.from(String(value || ''));
    return characters.length > limit ? `${characters.slice(0, limit).join('')}…` : characters.join('');
  }

  async function api(path, options = {}) {
    return window.QH_API.request(`/api${path}`, options);
  }

  function idempotencyKey(prefix) {
    return `${prefix}-${crypto.randomUUID()}`;
  }

  const practiceRetry = window.QH_PRACTICE_RETRY.createRetrySubmitter({
    request: api,
    keyFactory: idempotencyKey
  });

  function setBusy(element, busy, label) {
    if (!element) return;
    element.disabled = busy;
    element.setAttribute('aria-busy', String(busy));
    if (label) element.textContent = label;
  }

  function setRoute(rawRoute, pushHash = true) {
    const admin = currentUser?.roles?.includes('ADMIN');
    const allowed = admin
      ? ['theory', 'trainer', 'practice', 'coach', 'moderation']
      : ['theory', 'trainer', 'practice', 'coach'];
    const route = allowed.includes(rawRoute) ? rawRoute : 'theory';
    const viewRoute = ['practice', 'coach'].includes(route) ? 'learning' : route;
    closeModelPicker();
    $$('.view').forEach(view => view.classList.toggle('is-active', view.dataset.view === viewRoute));
    $$('.nav-link').forEach(link => link.classList.toggle('is-active', link.dataset.route === route));
    if (viewRoute === 'learning') syncLearningRoute(route);
    if (pushHash && location.hash !== `#${route}`) history.pushState(null, '', `#${route}`);
    window.scrollTo({ top: 0, behavior: 'smooth' });
    if (route === 'trainer' && !trainerIssuance) loadTrainerCard();
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
      ${renderWorkedExample(category)}
      ${renderExercises(category)}
      <div class="warning-box"><b>!</b><p><strong>Анти-паттерн.</strong> ${escapeHtml(category.mistake)}</p></div>
      <div class="cue-line"><strong>Контрольный сигнал:</strong> ${escapeHtml(category.cue)}</div>
      ${renderHistoricalCases(category)}
      <section class="theory-evidence"><div class="expansion-head"><span>ДОКАЗАТЕЛЬНЫЙ СЛОЙ</span><strong>${evidence.length} материалов</strong></div>
        ${evidence.map(section => `<article class="evidence-card"><div class="evidence-meta"><span>${escapeHtml(EVIDENCE_LABELS[section.evidenceGrade] || 'Без оценки')}</span><h4>${escapeHtml(section.title)}</h4>${section.source ? `<a href="${escapeHtml(section.source.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(section.source.title)} ↗</a>` : ''}</div><p>${escapeHtml(section.content)}</p></article>`).join('')}
      </section>
      ${category.contrasts?.length ? `<section class="contrast-list"><h3>Не перепутать</h3>${category.contrasts.map(item => `<p><strong>${escapeHtml(item.otherName)}:</strong> ${escapeHtml(item.text)}</p>`).join('')}</section>` : ''}`;
  }

  function renderWorkedExample(category) {
    const example = category.workedExample;
    if (!example) return '';
    const confusionName = categories.find(item => item.code === example.confusion?.otherCategory)?.name
      || example.confusion?.otherCategory || 'соседняя техника';
    const questionTemplates = category.questionTemplates || [];
    return `
      <section class="worked-example">
        <div class="worked-head"><span>РАЗОБРАННЫЙ ПРИМЕР</span><h3>${escapeHtml(example.title)}</h3></div>
        <div class="worked-situation"><strong>Ситуация</strong><p>${escapeHtml(example.situation)}</p></div>
        <div class="worked-questions">
          <article class="worked-question"><span>ОБЫЧНЫЙ ВОПРОС</span><blockquote>«${escapeHtml(example.ordinaryQuestion)}»</blockquote></article>
          <article class="worked-question is-hacker"><span>ВОПРОС-ВЗЛОМЩИК</span><blockquote>«${escapeHtml(example.hackerQuestion)}»</blockquote></article>
        </div>
        <ol class="reasoning-chain">
          ${example.reasoningSteps.map(step => `<li><span>${escapeHtml(step.label)}</span><p>${escapeHtml(step.text)}</p></li>`).join('')}
        </ol>
        <div class="worked-outcomes">
          <article class="worked-solution"><span>РЕШЕНИЕ / ЭКСПЕРИМЕНТ</span><p>${escapeHtml(example.solution)}</p></article>
          <article class="worked-classification"><span>ПОЧЕМУ ЭТО ${escapeHtml(category.name.toUpperCase())}</span><p>${escapeHtml(example.whyItFits)}</p><p><strong>Не перепутать с «${escapeHtml(confusionName)}».</strong> ${escapeHtml(example.confusion?.explanation)}</p></article>
        </div>
        <div class="question-template-list">
          ${questionTemplates.slice(0, 2).map(item => `<article><span>${escapeHtml(item.domain)}</span><p>«${escapeHtml(item.question)}»</p></article>`).join('')}
        </div>
      </section>`;
  }

  function renderExercises(category) {
    if (!category.quickExercise || !category.experiment) return '';
    return `
      <div class="exercise-pair applied-exercises">
        <section><span>15 МИНУТ</span><h4>Попробуйте технику</h4><p>${escapeHtml(category.quickExercise)}</p></section>
        <section><span>24–48 ЧАСОВ</span><h4>Полевой эксперимент</h4><p>${escapeHtml(category.experiment)}</p></section>
      </div>`;
  }

  function renderHistoricalCases(category) {
    if (!category.cases?.length) return '';
    const classificationLabel = value => value === 'explicit'
      ? 'Метод явно описан участником'
      : 'Ретроспективная интерпретация исследования';
    return `
      <section class="historical-cases">
        <div class="expansion-head"><span>ЛЮДИ И КОМПАНИИ</span><strong>${category.cases.length} доказательных кейса</strong></div>
        <div class="case-list">
          ${category.cases.map((item, index) => `
            <details class="case-card">
              <summary><span>${String(index + 1).padStart(2, '0')}</span><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.actor)} · ${escapeHtml(item.period)}</small></summary>
              <div class="case-content">
                <dl>
                  <div><dt>Исходная рамка</dt><dd>${escapeHtml(item.originalFrame)}</dd></div>
                  <div><dt>Сдвиг рамки</dt><dd>${escapeHtml(item.frameShift)}</dd></div>
                  <div><dt>Действие</dt><dd>${escapeHtml(item.action)}</dd></div>
                  <div><dt>Результат</dt><dd>${escapeHtml(item.outcome)}</dd></div>
                  <div><dt>Почему это техника</dt><dd>${escapeHtml(item.whyItFits)}</dd></div>
                  <div><dt>Ограничения</dt><dd>${escapeHtml(item.limitations)}</dd></div>
                </dl>
                <p class="case-classification">${escapeHtml(classificationLabel(item.classification))}</p>
                <p class="case-sources">${item.sources.map(source => `<a href="${escapeHtml(source.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(source.title)}</a>`).join(' · ')}</p>
              </div>
            </details>`).join('')}
        </div>
      </section>`;
  }

  function setTrainerFace(flipped) {
    const card = $('#trainer-card');
    const front = $('#trainer-front');
    const back = $('#trainer-feedback');
    card.classList.toggle('is-flipped', flipped);
    front.setAttribute('aria-hidden', String(flipped));
    back.setAttribute('aria-hidden', String(!flipped));
    front.inert = flipped;
    back.inert = !flipped;
  }

  function waitForTrainerTurn() {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return Promise.resolve();
    }
    return new Promise(resolve => {
      const inner = $('.trainer-card-inner');
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        window.clearTimeout(fallback);
        resolve();
      };
      const fallback = window.setTimeout(finish, 700);
      inner.addEventListener('transitionend', finish, { once: true });
    });
  }

  async function loadTrainerCard() {
    const card = $('#trainer-card');
    const difficulty = $('#difficulty-select').value;
    const sequence = ++trainerLoadSequence;
    $('#difficulty-select').disabled = true;
    $('#next-card')?.setAttribute('disabled', '');
    if (card.classList.contains('is-flipped')) {
      setTrainerFace(false);
      await waitForTrainerTurn();
      if (sequence !== trainerLoadSequence) return;
    }
    card.setAttribute('aria-busy', 'true');
    $('#trainer-error').textContent = '';
    $('#trainer-feedback').innerHTML = '';
    trainerFeedback = null;
    trainerSelection = null;
    try {
      const suffix = difficulty ? `?difficulty=${encodeURIComponent(difficulty)}` : '';
      const issuance = await api(`/trainer/next${suffix}`);
      if (sequence !== trainerLoadSequence) return;
      trainerIssuance = issuance;
      renderTrainerCard(trainerIssuance.card);
      $('#scenario-text').focus({ preventScroll: true });
    } catch (error) {
      if (sequence !== trainerLoadSequence) return;
      renderTrainerLoadError(error);
    } finally {
      if (sequence === trainerLoadSequence) {
        card.setAttribute('aria-busy', 'false');
        $('#difficulty-select').disabled = false;
      }
    }
  }

  function renderTrainerLoadError(error) {
    trainerIssuance = null;
    $('#scenario-domain').textContent = '—';
    $('#scenario-text').textContent = error.message;
    $('#scenario-question').textContent = '';
    $('#answer-grid').innerHTML = '';
    $('#trainer-rationale').value = '';
    $('#answer-fieldset').disabled = true;
    $('#trainer-rationale').disabled = true;
    setBusy($('#submit-trainer'), false, 'Проверить на сервере →');
    $('#submit-trainer').disabled = true;
    $('#card-counter').textContent = 'Карточка недоступна';
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
    setBusy($('#submit-trainer'), false, 'Проверить на сервере →');
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
    panel.classList.toggle('passed', value.correct);
    panel.innerHTML = `
      <div class="feedback-verdict">${value.correct ? 'Верно' : 'Нужно различить'} · ${escapeHtml(category?.name || value.correctCategory)}</div>
      <div class="feedback-score">${Math.round(value.mastery.score)}<small>/ 100</small></div>
      <h3 id="trainer-result-title" tabindex="-1">${value.correct ? 'Операция распознана.' : 'Категория определяется операцией вопроса.'}</h3>
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
    setTrainerFace(true);
    $('#trainer-result-title').focus({ preventScroll: true });
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

  function requestResetTrainerProgress() {
    $('#reset-trainer-dialog').showModal();
  }

  async function confirmResetTrainerProgress(event) {
    event.preventDefault();
    const button = $('#reset-trainer-confirm');
    if (button.disabled) return;
    setBusy(button, true, 'Сбрасываем…');
    try {
      await api('/progress', { method: 'DELETE' });
      $('#reset-trainer-dialog').close();
      await refreshProgressView();
      await loadTrainerCard();
      showToast('Прогресс тренажёра сброшен');
    } catch (error) {
      showToast(error.message);
    } finally {
      setBusy(button, false, 'Сбросить прогресс');
    }
  }

  function bindTrainer() {
    $('#submit-trainer').addEventListener('click', submitTrainer);
    $('#refresh-progress').addEventListener('click', refreshProgressView);
    $('#reset-trainer-progress').addEventListener('click', requestResetTrainerProgress);
    $('#reset-trainer-form').addEventListener('submit', confirmResetTrainerProgress);
    $('#reset-trainer-cancel').addEventListener('click', () => $('#reset-trainer-dialog').close());
    $('#difficulty-select').addEventListener('change', loadTrainerCard);
  }

  function modelPresentation(model) {
    const match = String(model || '').match(/^(.+?)(?:\[(x?high)\])?$/i);
    const rawFamily = match?.[1] || 'Модель';
    const reasoning = match?.[2]?.toLowerCase();
    const words = rawFamily.split('-');
    const family = rawFamily
      .replace(/^gpt-/i, 'GPT-')
      .replace(/-([a-z])/gi, (_, letter) => ` ${letter.toUpperCase()}`);
    return {
      family,
      mark: words.at(-1)?.charAt(0).toUpperCase() || '?',
      detail: reasoning === 'xhigh'
        ? 'Extra high · максимум анализа'
        : reasoning === 'high' ? 'High · сбалансировано' : 'Стандартный режим'
    };
  }

  function renderModelPicker(root) {
    const trigger = $('.model-trigger', root);
    const popover = $('.model-popover', root);
    const select = $('.model-select', root);
    const presentation = modelPresentation(selectedModel);
    trigger.disabled = !availableModels.length;
    $('.model-mark', root).textContent = availableModels.length ? presentation.mark : '—';
    $('.model-name', root).textContent = availableModels.length ? presentation.family : 'Модель недоступна';
    $('.model-detail', root).textContent = availableModels.length ? presentation.detail : 'Нет доступных моделей';
    select.innerHTML = availableModels.map(model => `<option value="${escapeHtml(model)}">${escapeHtml(model)}</option>`).join('');
    select.disabled = !availableModels.length;
    select.value = selectedModel || '';
    popover.innerHTML = availableModels.map(model => {
      const option = modelPresentation(model);
      const selected = model === selectedModel;
      return `<button class="model-option${selected ? ' is-selected' : ''}" type="button" role="option" aria-selected="${selected}" tabindex="${selected ? '0' : '-1'}" data-model="${escapeHtml(model)}">
        <span class="model-mark">${escapeHtml(option.mark)}</span><span class="model-copy"><strong>${escapeHtml(option.family)}</strong><small>${escapeHtml(option.detail)}</small></span><span class="model-check" aria-hidden="true">${selected ? '✓' : ''}</span>
      </button>`;
    }).join('');
    $$('.model-option', popover).forEach(option => {
      option.addEventListener('click', () => {
        setSelectedModel(option.dataset.model);
        closeModelPicker(root, { restoreFocus: true });
      });
      option.addEventListener('keydown', handleModelOptionKeydown);
    });
  }

  function renderModelPickers() {
    $$('.model-picker').forEach(renderModelPicker);
  }

  function setSelectedModel(model) {
    selectedModel = model || null;
    renderModelPickers();
  }

  function openModelPicker(root, focusIndex = null) {
    if (!availableModels.length) return;
    const trigger = $('.model-trigger', root);
    const popover = $('.model-popover', root);
    popover.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    root.classList.add('is-open');
    const selectedIndex = Math.max(0, availableModels.indexOf(selectedModel));
    focusModelOption(root, focusIndex == null ? selectedIndex : focusIndex);
  }

  function closeModelPicker(root = null, { restoreFocus = false } = {}) {
    if (!root) {
      $$('.model-picker').forEach(picker => closeModelPicker(picker));
      return;
    }
    const trigger = $('.model-trigger', root);
    const popover = $('.model-popover', root);
    if (!trigger || !popover) return;
    popover.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    root.classList.remove('is-open');
    if (restoreFocus) trigger.focus();
  }

  function moveModelFocus(root, currentIndex, delta) {
    const options = $$('.model-option', root);
    if (!options.length) return;
    focusModelOption(root, (currentIndex + delta + options.length) % options.length);
  }

  function focusModelOption(root, index) {
    const options = $$('.model-option', root);
    if (!options.length) return;
    const target = Math.max(0, Math.min(options.length - 1, index));
    options.forEach((option, optionIndex) => { option.tabIndex = optionIndex === target ? 0 : -1; });
    options[target].focus();
  }

  function handleModelOptionKeydown(event) {
    const root = event.currentTarget.closest('.model-picker');
    const options = $$('.model-option', root);
    const index = options.indexOf(event.currentTarget);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      moveModelFocus(root, index, event.key === 'ArrowDown' ? 1 : -1);
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      focusModelOption(root, event.key === 'Home' ? 0 : options.length - 1);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      closeModelPicker(root, { restoreFocus: true });
    }
  }

  function bindModelPickers() {
    $$('.model-picker').forEach(root => {
      const trigger = $('.model-trigger', root);
      const popover = $('.model-popover', root);
      trigger.addEventListener('click', () => {
        if (popover.hidden) openModelPicker(root); else closeModelPicker(root);
      });
      trigger.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault();
          const selectedIndex = Math.max(0, availableModels.indexOf(selectedModel));
          openModelPicker(root, event.key === 'ArrowDown' ? selectedIndex : Math.max(0, availableModels.length - 1));
        } else if (event.key === 'Escape') {
          closeModelPicker(root);
        }
      });
      $('.model-select', root).addEventListener('change', event => setSelectedModel(event.target.value));
      root.addEventListener('focusout', () => {
        window.setTimeout(() => {
          if (!root.contains(document.activeElement)) closeModelPicker(root);
        }, 0);
      });
    });
    document.addEventListener('click', event => {
      $$('.model-picker').forEach(root => {
        if (!root.contains(event.target)) closeModelPicker(root);
      });
    });
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
      availableModels = (status.models || []).slice(0, 3);
      setSelectedModel(availableModels.includes(status.defaultModel) ? status.defaultModel : availableModels[0] || null);
    } catch (error) {
      systemStatus = null;
      systemStatusError = error.message;
      $('#connection-dot').classList.remove('online', 'fallback');
      $('#agent-dot').classList.remove('online', 'fallback');
      $('#connection-label').textContent = 'сервер недоступен';
      $('#agent-status').textContent = error.message;
      $('#agent-command').textContent = '—';
      availableModels = [];
      setSelectedModel(null);
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

  function syncLearningRoute(route) {
    const chat = route === 'coach';
    const practicePanel = $('#practice-panel');
    const practiceHistory = $('#practice-history-tools');
    const messageFeed = $('#message-feed');
    const composer = $('#composer');
    const sessionTools = $('#session-tools');

    practicePanel.classList.toggle('is-hidden', chat);
    practicePanel.inert = chat;
    practiceHistory.classList.toggle('is-hidden', chat);
    practiceHistory.inert = chat;
    $('#view-learning').classList.toggle('is-practice', !chat);
    messageFeed.classList.toggle('is-hidden', !chat);
    messageFeed.inert = !chat;
    composer.classList.toggle('is-hidden', !chat);
    composer.inert = !chat;
    sessionTools.classList.toggle('is-hidden', !chat);
    sessionTools.inert = !chat;
    $('#learning-section-label').textContent = chat ? '04 / КОУЧ' : '03 / ПРАКТИКА';
    $('#learning-sidebar-title').textContent = chat ? 'Диалоги' : 'Полный цикл';
    $('#chat-title').textContent = chat ? 'Тренер вопросов' : 'Практика полного цикла';
    if (chat) initChat();
    else initPractice();
  }

  async function initPractice() {
    if (practiceInitialized) return;
    practiceInitialized = true;
    await Promise.allSettled([loadPracticeCycles(), loadPracticeExample()]);
  }

  async function loadPracticeCycles() {
    try {
      practiceCycles = await api('/practice/cycles');
      renderPracticeCycles();
    } catch (error) {
      showToast(error.message);
    }
  }

  function renderPracticeCycles() {
    const selectedId = practiceAssignment?.assignmentId;
    $('#practice-cycle-list').innerHTML = practiceCycles.map(cycle => `
      <div role="listitem">
        <button class="practice-cycle-row" type="button" data-practice-cycle="${escapeHtml(cycle.assignmentId)}">
          <span class="practice-cycle-meta"><span>${escapeHtml(cycle.targetCategory.name)}</span><span>${escapeHtml(practiceStatusLabel(cycle.status))}</span></span>
          <strong>${escapeHtml(truncateText(cycle.situation, 100))}</strong>
          <small>${escapeHtml(cycle.domain)} · ${escapeHtml(formatDate(cycle.updatedAt))}${cycle.attemptCount ? ` · попыток: ${cycle.attemptCount}` : ''}</small>
        </button>
      </div>`).join('');
    $$('.practice-cycle-row', $('#practice-cycle-list')).forEach(button => {
      const active = button.dataset.practiceCycle === selectedId;
      button.setAttribute('aria-current', String(active));
      button.addEventListener('click', () => selectPracticeCycle(button.dataset.practiceCycle));
    });
    syncPracticeAvailability();
  }

  function setPracticeAvailability(code, message = '') {
    const region = $('#practice-availability');
    region.dataset.code = code || '';
    region.textContent = message;
  }

  function syncPracticeAvailability() {
    [$('#start-practice'), $('#new-practice')].forEach(button => {
      if (!button) return;
      button.disabled = practiceSubmitting;
      button.title = '';
    });
  }

  function practiceStatusLabel(status) {
    return ({
      DRAFT: 'Черновик', EVALUATING: 'На оценке', PASSED: 'Зачёт',
      NEEDS_REVISION: 'На доработке', UNVERIFIED: 'Не проверено'
    })[status] || status || 'Черновик';
  }

  async function loadPracticeExample() {
    try {
      const example = await api('/practice/examples/random');
      $('#practice-example-category').textContent = `ПРИМЕР · ${example.targetCategory.name}`;
      $('#practice-example-situation').textContent = `${example.domain}. ${example.situation}`;
      Object.keys(FIELD_LABELS).forEach(field => {
        $(`#practice-example-${field}`).textContent = example[field];
      });
      $('#practice-example-recommendation').textContent = example.recommendation;
    } catch (error) {
      $('#practice-example-category').textContent = 'ПРИМЕР ВРЕМЕННО НЕДОСТУПЕН';
      $('#practice-example-situation').textContent = error.message;
    }
  }

  async function startPractice() {
    const buttons = [$('#start-practice'), $('#new-practice')];
    if (practiceSubmitting || buttons.some(button => button.disabled)) return;
    buttons.forEach(button => setBusy(button, true));
    try {
      await flushPracticeDraft();
      clearAttemptPoll();
      const assignment = await api('/practice/assignments', { method: 'POST', body: '{}' });
      setPracticeAvailability(null);
      await loadPracticeCycles();
      await selectPracticeCycle(assignment.assignmentId, { skipFlush: true });
      $('#practice-question').focus();
    } catch (error) {
      const code = error.problem?.code;
      if (code === PRACTICE_CATALOG_EXHAUSTED) {
        const message = error.message || PRACTICE_EXHAUSTED_MESSAGE;
        setPracticeAvailability(code, message);
        showToast(message);
      } else {
        showToast(error.message);
      }
    } finally {
      buttons.forEach(button => setBusy(button, false));
      $('#new-practice').textContent = '＋ Новая ситуация';
      $('#start-practice').innerHTML = 'Получить ситуацию <span>→</span>';
      syncPracticeAvailability();
    }
  }

  async function selectPracticeCycle(assignmentId, options = {}) {
    if (practiceSubmitting && !options.afterSubmission) return;
    if (!options.skipFlush) await flushPracticeDraft();
    clearAttemptPoll();
    const sequence = ++practiceLoadSequence;
    try {
      const cycle = await api(`/practice/cycles/${assignmentId}`);
      if (sequence !== practiceLoadSequence) return;
      renderPracticeCycle(cycle, options.focusFeedback === true);
      renderPracticeCycles();
    } catch (error) {
      if (sequence !== practiceLoadSequence) return;
      showToast(error.message);
    }
  }

  function resetPracticeHint(hint) {
    const toggle = $('#practice-hint-toggle');
    const content = $('#practice-hint');
    hint = String(hint || '').trim();
    content.textContent = hint;
    content.hidden = true;
    toggle.hidden = !hint;
    toggle.setAttribute('aria-expanded', 'false');
    toggle.textContent = 'Показать подсказку';
  }

  function togglePracticeHint() {
    const toggle = $('#practice-hint-toggle');
    const content = $('#practice-hint');
    const reveal = content.hidden;
    content.hidden = !reveal;
    toggle.setAttribute('aria-expanded', String(reveal));
    toggle.textContent = reveal ? 'Скрыть подсказку' : 'Показать подсказку';
  }

  function renderPracticeCycle(cycle, focusFeedback = false) {
    practiceRetry.reset();
    practiceSubmitting = false;
    practiceAssignment = cycle.assignment;
    practiceAttempt = cycle.attempts.at(-1) || null;
    practiceEditorBaseAttemptId = cycle.editor.baseAttemptId;
    practiceEditableFields = cycle.editor.editableFields;
    practiceDraftDirty = false;
    $('#practice-empty').classList.add('is-hidden');
    $('#practice-workspace').classList.remove('is-hidden');
    $('#practice-domain').textContent = `${practiceAssignment.domain} · ${practiceAssignment.targetCategory.name}`;
    $('#practice-situation').textContent = practiceAssignment.situation;
    $('#practice-guidance').textContent = practiceAssignment.targetCategory.guidance;
    resetPracticeHint(practiceAssignment.hint);
    Object.keys(FIELD_LABELS).forEach(field => {
      $(`#practice-${field}`).value = cycle.editor[field] || '';
    });
    const locked = cycle.editor.editableFields.length === 0;
    setRevisionFields(practiceEditableFields, locked);
    $('#practice-attempt').textContent = practiceAttempt
      ? `Попытка ${practiceAttempt.attemptNumber} · ${practiceStatusLabel(practiceAttempt.status)}`
      : 'Попытка 1 · черновик';
    $('#practice-save-status').textContent = cycle.draft
      ? `Сохранено ${formatTime(cycle.draft.updatedAt)}` : locked ? 'Цикл завершён' : 'Черновик на сервере';
    $('#practice-save-status').classList.remove('is-error');
    $('#practice-error').textContent = '';
    renderPracticeTimeline(cycle.attempts);
    $('#practice-feedback').classList.add('is-hidden');
    if (practiceAttempt?.assessment && TERMINAL_ATTEMPT_STATUSES.has(practiceAttempt.status)) {
      renderPracticeFeedback(practiceAttempt, focusFeedback);
    } else {
      setBusy($('#submit-practice'), false, practiceAttempt?.status === 'EVALUATING' ? 'Оцениваем…' : 'Отправить на оценку →');
      $('#submit-practice').disabled = locked;
      updatePracticeProgress(practiceAttempt?.status === 'EVALUATING');
      if (practiceAttempt?.status === 'EVALUATING') followAttempt(practiceAttempt.attemptId);
    }
  }

  function renderPracticeTimeline(attempts) {
    $('#practice-timeline').innerHTML = attempts.map(attempt => {
      const assessment = attempt.assessment;
      const recommendation = assessment
        ? `${assessment.feedback || ''}${assessment.priorityCorrection?.what ? ` ${assessment.priorityCorrection.what}: ${assessment.priorityCorrection.why}` : ''}`
        : 'Модель оценивает полный цикл…';
      return `<article class="practice-attempt-card">
        <header class="practice-attempt-head"><span>Попытка ${attempt.attemptNumber}</span><span>${escapeHtml(practiceStatusLabel(attempt.status))} · ${escapeHtml(formatDate(attempt.createdAt))}</span></header>
        <div class="practice-attempt-steps">
          ${Object.keys(FIELD_LABELS).map(field => `<section class="practice-attempt-step"><span>${escapeHtml(FIELD_LABELS[field])}</span><p>${escapeHtml(attempt[field])}</p></section>`).join('')}
        </div>
        <footer class="practice-attempt-model"><strong>Рекомендация модели</strong><p>${escapeHtml(recommendation.trim())}</p></footer>
      </article>`;
    }).join('');
  }

  function practiceValues() {
    return Object.fromEntries(Object.keys(FIELD_LABELS).map(field => [field, $(`#practice-${field}`).value.trim()]));
  }

  function schedulePracticeDraft() {
    if (!practiceAssignment || $('#submit-practice').disabled) return;
    practiceDraftDirty = true;
    $('#practice-save-status').textContent = 'Есть несохранённые изменения';
    $('#practice-save-status').classList.remove('is-error');
    window.clearTimeout(practiceDraftTimer);
    practiceDraftTimer = window.setTimeout(() => {
      practiceDraftTimer = null;
      savePracticeDraft().catch(() => {});
    }, 650);
  }

  function savePracticeDraft() {
    if (!practiceAssignment || !practiceDraftDirty) return practiceDraftPromise || Promise.resolve();
    const assignmentId = practiceAssignment.assignmentId;
    const draftPath = `/practice/cycles/${practiceAssignment.assignmentId}/draft`;
    const payload = { baseAttemptId: practiceEditorBaseAttemptId, ...practiceValues() };
    practiceDraftDirty = false;
    const previous = practiceDraftPromise || Promise.resolve();
    practiceDraftPromise = previous.catch(() => {}).then(() => api(draftPath, {
      method: 'PUT', body: JSON.stringify(payload)
    })).then(draft => {
      if (practiceAssignment?.assignmentId === assignmentId && !practiceDraftDirty) {
        $('#practice-save-status').textContent = `Сохранено ${formatTime(draft.updatedAt)}`;
        $('#practice-save-status').classList.remove('is-error');
      }
      loadPracticeCycles();
      return draft;
    }).catch(error => {
      if (practiceAssignment?.assignmentId === assignmentId) {
        practiceDraftDirty = true;
        $('#practice-save-status').textContent = 'Не удалось сохранить черновик';
        $('#practice-save-status').classList.add('is-error');
      }
      throw error;
    }).finally(() => {
      if (practiceDraftPromise === current) practiceDraftPromise = null;
    });
    const current = practiceDraftPromise;
    return current;
  }

  async function flushPracticeDraft() {
    if (practiceDraftTimer) {
      window.clearTimeout(practiceDraftTimer);
      practiceDraftTimer = null;
    }
    try {
      if (practiceDraftDirty) await savePracticeDraft();
      else if (practiceDraftPromise) await practiceDraftPromise;
    } catch (_) {
      // A failed autosave must not discard local input or block an explicit submission.
    }
  }

  function validatePractice(values, revisionFields = null) {
    const minimums = { question: 30, rationale: 40, solution: 35 };
    const fields = revisionFields?.length ? revisionFields : Object.keys(FIELD_LABELS);
    for (const field of fields) {
      if (values[field].length < minimums[field]) return `${FIELD_LABELS[field]}: нужно не менее ${minimums[field]} содержательных символов.`;
    }
    return '';
  }

  async function submitPractice(event) {
    event.preventDefault();
    if (!practiceAssignment || practiceSubmitting) return;
    const assignment = practiceAssignment;
    const attempt = practiceAttempt;
    const values = practiceValues();
    const fieldsToRevise = attempt?.assessment?.fieldsToRevise || [];
    const editableFields = ['NEEDS_REVISION', 'UNVERIFIED'].includes(attempt?.status)
      ? practiceEditableFields : null;
    const error = validatePractice(values, editableFields);
    if (error) {
      $('#practice-error').textContent = error;
      return;
    }
    practiceSubmitting = true;
    setBusy($('#submit-practice'), true, 'Оцениваем…');
    setRevisionFields([], true);
    updatePracticeProgress(true);
    await flushPracticeDraft();
    $('#practice-error').textContent = '';
    const revision = attempt?.status === 'NEEDS_REVISION';
    const retry = attempt?.status === 'UNVERIFIED';
    const path = revision
      ? `/practice/attempts/${attempt.attemptId}/revisions`
      : '/practice/attempts';
    const body = revision
      ? Object.fromEntries([...fieldsToRevise.map(field => [field, values[field]]), ['model', selectedModel], ['idempotencyKey', idempotencyKey('revision')]])
      : { assignmentId: assignment.assignmentId, ...values, model: selectedModel, idempotencyKey: idempotencyKey('attempt') };
    try {
      practiceAttempt = retry
        ? await practiceRetry.submit({ attemptId: attempt.attemptId, values, model: selectedModel })
        : await api(path, { method: 'POST', body: JSON.stringify(body) });
      practiceDraftDirty = false;
      $('#practice-attempt').textContent = `Попытка ${practiceAttempt.attemptNumber} · оценка сервера`;
      await loadPracticeCycles();
      await followAttempt(practiceAttempt.attemptId);
    } catch (requestError) {
      practiceSubmitting = false;
      $('#practice-error').textContent = requestError.message;
      setBusy($('#submit-practice'), false, retry ? 'Повторить проверку →' : 'Отправить на оценку →');
      setRevisionFields(revision ? fieldsToRevise : retry ? practiceEditableFields : []);
      updatePracticeProgress(false);
    }
  }

  async function followAttempt(attemptId) {
    clearAttemptPoll();
    const poll = async () => {
      try {
        practiceAttempt = await api(`/practice/attempts/${attemptId}`);
        if (TERMINAL_ATTEMPT_STATUSES.has(practiceAttempt.status)) {
          clearAttemptPoll();
          practiceSubmitting = false;
          await loadPracticeCycles();
          await selectPracticeCycle(practiceAttempt.assignmentId, { skipFlush: true, focusFeedback: true, afterSubmission: true });
          return;
        }
        attemptPoll = window.setTimeout(poll, 900);
      } catch (error) {
        clearAttemptPoll();
        practiceSubmitting = false;
        $('#practice-error').textContent = error.message;
        setRevisionFields([], true);
        setBusy($('#submit-practice'), true, 'Проверка продолжается…');
      }
    };
    await poll();
  }

  function clearAttemptPoll() {
    if (attemptPoll) window.clearTimeout(attemptPoll);
    attemptPoll = null;
  }

  function renderPracticeFeedback(attempt, focus = true) {
    practiceSubmitting = false;
    syncPracticeAvailability();
    const assessment = attempt.assessment;
    const passed = attempt.status === 'PASSED';
    const unverified = attempt.status === 'UNVERIFIED';
    const panel = $('#practice-feedback');
    panel.classList.remove('is-hidden', 'passed');
    panel.classList.toggle('passed', passed);
    const fit = assessment.categoryFitScore == null ? '—' : `${assessment.categoryFitScore}/3`;
    const strength = assessment.questionStrengthScore == null ? '—' : `${assessment.questionStrengthScore}/4`;
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
      <div class="back-actions">${passed ? '<button class="primary-button" id="practice-next" type="button">Следующая ситуация <span>→</span></button>' : unverified ? '<button class="primary-button" id="practice-retry" type="button">Повторить проверку <span>→</span></button>' : '<button class="primary-button" id="practice-revise" type="button">Перейти к исправлению <span>→</span></button>'}</div>`;
    setBusy($('#submit-practice'), false, passed ? 'Зачтено' : unverified ? 'Повторить проверку →' : 'Отправить исправление →');
    $('#submit-practice').disabled = passed;
    if (passed) $('#practice-next').addEventListener('click', startPractice);
    if (unverified) $('#practice-retry').addEventListener('click', () => focusFirstRevision(practiceEditableFields));
    if (!passed && !unverified) {
      setRevisionFields(assessment.fieldsToRevise);
      $('#practice-revise').addEventListener('click', () => focusFirstRevision(assessment.fieldsToRevise));
    }
    updatePracticeProgress(false, passed);
    if (focus) {
      panel.focus({ preventScroll: true });
      panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }

  function setRevisionFields(fields, locked = false) {
    const revision = !locked && practiceAttempt?.status === 'NEEDS_REVISION' && fields.length > 0;
    const retry = !locked && practiceAttempt?.status === 'UNVERIFIED' && fields.length > 0;
    const form = $('#practice-form');
    const intro = $('#practice-revision-intro');
    intro.hidden = !revision && !retry;
    form.classList.toggle('is-revision', revision || retry);
    if (revision) {
      $('#practice-revision-title').textContent = `Исправление попытки ${practiceAttempt.attemptNumber}`;
      $('#practice-revision-fields').textContent = `Измените: ${fields.map(field => FIELD_LABELS[field] || field).join(', ')}.`;
      setBusy($('#submit-practice'), false, 'Отправить исправление →');
    }
    if (retry) {
      $('#practice-revision-title').textContent = `Повторная проверка попытки ${practiceAttempt.attemptNumber}`;
      $('#practice-revision-fields').textContent = `Можно изменить: ${fields.map(field => FIELD_LABELS[field] || field).join(', ')}.`;
      setBusy($('#submit-practice'), false, 'Повторить проверку →');
    }
    Object.keys(FIELD_LABELS).forEach(field => {
      const input = $(`#practice-${field}`);
      const restricted = revision || retry;
      const editable = !locked && (!restricted || fields.includes(field));
      input.disabled = !editable;
      input.classList.toggle('needs-revision', revision && editable);
    });
  }

  function focusFirstRevision(fields) {
    $('#practice-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (fields[0]) $(`#practice-${fields[0]}`)?.focus({ preventScroll: true });
  }

  function updatePracticeProgress(evaluating = false, passed = false) {
    const values = practiceValues();
    $$('#practice-workspace-progress span').forEach((item, index) => {
      const field = Object.keys(FIELD_LABELS)[index];
      item.classList.toggle('is-active', passed || evaluating || values[field]?.length > 0);
    });
  }

  function bindPractice() {
    $('#start-practice').addEventListener('click', startPractice);
    $('#new-practice').addEventListener('click', startPractice);
    $('#practice-hint-toggle').addEventListener('click', togglePracticeHint);
    $('#practice-form').addEventListener('submit', submitPractice);
    $('#practice-form').addEventListener('input', () => {
      updatePracticeProgress();
      schedulePracticeDraft();
    });
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
    chatSessions = sessions;
    $('#session-list').innerHTML = sessions.map(session => {
      const active = session.id === currentSessionId ? 'is-active' : '';
      if (session.id === editingSessionId) {
        return `<div class="session-row is-editing ${active}"><form class="session-rename-form" data-rename-form="${session.id}">
          <div class="session-item session-rename-fields">
            <label class="visually-hidden" for="session-title-${session.id}">Название диалога</label>
            <input class="session-title-input" id="session-title-${session.id}" maxlength="180" value="${escapeHtml(editingSessionValue)}" autocomplete="off">
            <small>${formatDate(session.updatedAt)}</small>
          </div>
          <button class="session-rename-save" type="submit" aria-label="Сохранить название">✓</button>
          <button class="session-rename-cancel" type="button" data-cancel-rename aria-label="Отменить переименование">×</button>
        </form></div>`;
      }
      return `<div class="session-row ${active}"><button class="session-item" data-session="${session.id}"><strong>${escapeHtml(session.title)}</strong><small>${formatDate(session.updatedAt)}</small></button><button class="session-rename" data-rename-session="${session.id}" aria-label="Переименовать диалог">✎</button><button class="session-delete" data-delete-session="${session.id}" data-title="${escapeHtml(session.title)}" aria-label="Удалить диалог">×</button></div>`;
    }).join('');
    $$('.session-item').forEach(button => button.addEventListener('click', () => selectSession(button.dataset.session)));
    $$('.session-rename').forEach(button => button.addEventListener('click', () => beginSessionRename(button.dataset.renameSession)));
    $$('.session-delete').forEach(button => button.addEventListener('click', () => requestDeleteSession(button.dataset.deleteSession, button.dataset.title)));
    $$('.session-rename-form').forEach(form => bindSessionRenameForm(form));
  }

  function beginSessionRename(sessionId) {
    const session = chatSessions.find(item => item.id === sessionId);
    if (!session) return;
    editingSessionId = sessionId;
    editingSessionValue = session.title;
    renderSessions(chatSessions);
    const input = $(`#session-title-${sessionId}`);
    input?.focus();
    input?.select();
  }

  function cancelSessionRename() {
    if (renameSubmitting) return;
    editingSessionId = null;
    editingSessionValue = '';
    renderSessions(chatSessions);
  }

  function bindSessionRenameForm(form) {
    const sessionId = form.dataset.renameForm;
    const input = $('.session-title-input', form);
    form.addEventListener('submit', event => {
      event.preventDefault();
      saveSessionRename(sessionId, input.value);
    });
    input.addEventListener('input', () => { editingSessionValue = input.value; });
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') {
        event.preventDefault();
        saveSessionRename(sessionId, input.value);
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        cancelSessionRename();
      }
    });
    input.addEventListener('blur', event => {
      if (event.relatedTarget?.closest('.session-row') === form.closest('.session-row')) return;
      window.setTimeout(() => {
        if (!renameSubmitting && editingSessionId === sessionId) cancelSessionRename();
      }, 0);
    });
    $('[data-cancel-rename]', form).addEventListener('click', cancelSessionRename);
  }

  async function saveSessionRename(sessionId, rawTitle) {
    if (renameSubmitting) return;
    const title = rawTitle.trim();
    if (!title) {
      showToast('Название не должно быть пустым');
      $(`#session-title-${sessionId}`)?.focus();
      return;
    }
    renameSubmitting = true;
    try {
      const updated = await api(`/chat/sessions/${sessionId}`, {
        method: 'PATCH', body: JSON.stringify({ title })
      });
      chatSessions = chatSessions.map(session => session.id === updated.id ? updated : session);
      editingSessionId = null;
      editingSessionValue = '';
      renderSessions(chatSessions);
    } catch (error) {
      showToast(error.message);
      $(`#session-title-${sessionId}`)?.focus();
    } finally {
      renameSubmitting = false;
    }
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
    const stream = window.QH_COACH_STREAM.createCoachStream({
      render: markdown => {
        const body = $(`#${streamId} .message-body`);
        if (body) body.innerHTML = renderMarkdown(markdown);
      }
    });
    source.addEventListener('snapshot', event => {
      stream.accept(JSON.parse(event.data));
    });
    source.addEventListener('done', event => {
      const payload = JSON.parse(event.data);
      source.close(); activeStream = null;
      stream.finish(payload);
      finishChat(streamId, stream.text(), payload.source || 'ACP');
      reloadSessions(currentSessionId).catch(() => {});
    });
    source.addEventListener('failure', event => {
      source.close(); activeStream = null;
      stream.dispose();
      finishChat(streamId, `## Агент недоступен\n\n${JSON.parse(event.data).message || 'Неизвестная ошибка'}`, 'ERROR');
    });
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) return;
      source.close(); activeStream = null;
      const markdown = stream.text();
      stream.dispose();
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

  function requestClearPracticeCycles() {
    $('#clear-practice-dialog').showModal();
  }

  function resetPracticeAfterAdminClear() {
    clearAttemptPoll();
    window.clearTimeout(practiceDraftTimer);
    practiceLoadSequence += 1;
    practiceAssignment = null;
    practiceAttempt = null;
    practiceCycles = [];
    practiceInitialized = false;
    practiceDraftTimer = null;
    practiceDraftPromise = null;
    practiceDraftDirty = false;
    practiceEditorBaseAttemptId = null;
    practiceSubmitting = false;
    $('#practice-cycle-list').innerHTML = '';
    $('#practice-workspace').classList.add('is-hidden');
    $('#practice-empty').classList.remove('is-hidden');
    $('#practice-feedback').classList.add('is-hidden');
    setPracticeAvailability(null);
    syncPracticeAvailability();
  }

  async function confirmClearPracticeCycles(event) {
    event.preventDefault();
    const button = $('#clear-practice-confirm');
    if (button.disabled) return;
    setBusy(button, true, 'Удаляем…');
    try {
      const result = await api('/admin/practice/cycles', { method: 'DELETE' });
      $('#clear-practice-dialog').close();
      resetPracticeAfterAdminClear();
      showToast(`Удалено циклов практики: ${result.deletedCycles}`);
    } catch (error) {
      showToast(error.message);
    } finally {
      setBusy(button, false, 'Удалить все циклы');
    }
  }

  async function loadModeration() {
    if (!currentUser?.roles?.includes('ADMIN')) return;
    $('#moderation-status').textContent = 'Загружаем очередь…';
    try {
      moderationRows = await api(`/admin/scenario-candidates?status=${encodeURIComponent(moderationStatus)}`);
      if (selectedCandidate) {
        selectedCandidate = moderationRows.find(item => item.id === selectedCandidate.id) || null;
      }
      renderCandidateList();
      renderCandidateDetail();
      $('#moderation-status').textContent = `${STATUS_LABELS[moderationStatus]}: ${moderationRows.length}`;
    } catch (error) {
      $('#moderation-status').textContent = error.message;
    }
  }

  function renderCandidateList() {
    $('#candidate-list').innerHTML = moderationRows.length ? moderationRows.map(item => `
      <button class="candidate-row ${selectedCandidate?.id === item.id ? 'is-active' : ''}" data-candidate="${item.id}">
        <span>${escapeHtml(TARGET_LABELS[item.target] || item.target || '—')} · ${escapeHtml(item.difficulty || item.category || '—')}</span>
        <strong>${escapeHtml(firstCharacters(item.situation || 'Некорректный кандидат'))}</strong>
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
    panel.classList.toggle('is-empty', !selectedCandidate);
    if (!selectedCandidate) { panel.innerHTML = '<p>Выберите кандидат в очереди.</p>'; return; }
    const item = selectedCandidate;
    const editable = item.status === 'PENDING_REVIEW';
    const practice = item.target === 'PRACTICE';
    panel.innerHTML = `
      <form id="candidate-form" class="candidate-form">
        <div class="candidate-heading"><span>${escapeHtml(TARGET_LABELS[item.target] || item.target)} · ${escapeHtml(STATUS_LABELS[item.status] || item.status)} · v${item.version}</span><strong>${escapeHtml(item.sourceModel || 'модель не указана')}</strong></div>
        ${item.rejectionReasons?.length ? `<div class="moderation-reasons"><strong>Автоматические причины</strong>${item.rejectionReasons.map(reason => `<span>${escapeHtml(reason)}</span>`).join('')}</div>` : ''}
        ${practice ? `
          <div class="candidate-grid"><label>Категория<input name="category" value="${escapeHtml(item.category || '')}" readonly></label><label>Домен<input name="domain" value="${escapeHtml(item.domain || '')}" ${editable ? '' : 'disabled'}></label></div>
          <label>Ситуация<textarea name="situation" rows="7" ${editable ? '' : 'disabled'}>${escapeHtml(item.situation || '')}</textarea></label>
          <label>Подсказка<textarea name="hint" rows="3" ${editable ? '' : 'disabled'}>${escapeHtml(item.hint || '')}</textarea></label>
        ` : `
          <div class="candidate-grid"><label>Категория<input name="category" value="${escapeHtml(item.category || '')}" ${editable ? '' : 'disabled'}></label><label>Сложность<select name="difficulty" ${editable ? '' : 'disabled'}>${['L1','L2','L3'].map(level => `<option ${item.difficulty === level ? 'selected' : ''}>${level}</option>`).join('')}</select></label><label>Домен<input name="domain" value="${escapeHtml(item.domain || '')}" ${editable ? '' : 'disabled'}></label></div>
          <label>Ситуация<textarea name="situation" rows="5" ${editable ? '' : 'disabled'}>${escapeHtml(item.situation || '')}</textarea></label>
          <label>Вопрос<textarea name="question" rows="3" ${editable ? '' : 'disabled'}>${escapeHtml(item.question || '')}</textarea></label>
          <label>Подсказка<textarea name="hint" rows="2" ${editable ? '' : 'disabled'}>${escapeHtml(item.hint || '')}</textarea></label>
          <label>Объяснение<textarea name="explanation" rows="3" ${editable ? '' : 'disabled'}>${escapeHtml(item.explanation || '')}</textarea></label>
          <label>Варианты (коды через запятую)<input name="options" value="${escapeHtml((item.options || []).join(', '))}" ${editable ? '' : 'disabled'}></label>
          <div class="candidate-grid"><label>Правильная<input name="correctCategory" value="${escapeHtml(item.correctCategory || '')}" ${editable ? '' : 'disabled'}></label><label>Путают с<input name="confusedWith" value="${escapeHtml(item.confusedWith || '')}" ${editable ? '' : 'disabled'}></label></div>
          <label>Контраст<textarea name="contrast" rows="2" ${editable ? '' : 'disabled'}>${escapeHtml(item.contrast || '')}</textarea></label>
        `}
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
    if (selectedCandidate.target === 'PRACTICE') {
      return {
        category: selectedCandidate.category, secondaryCategory: null, difficulty: null,
        domain: form.get('domain'), situation: form.get('situation'), question: null,
        hint: form.get('hint'), options: [], correctCategory: null,
        explanation: null, confusedWith: null, contrast: null
      };
    }
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
      const target = selectedCandidate.target;
      await api(`/admin/scenario-candidates/${selectedCandidate.id}/approve`, { method: 'POST', body: JSON.stringify({ expectedVersion: selectedCandidate.version }) });
      selectedCandidate = null;
      renderCandidateDetail();
      showToast(target === 'PRACTICE' ? 'Кейс опубликован и доступен в практике' : 'Кейс опубликован и доступен в тренажёре');
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

  async function generateCandidates(target) {
    const buttons = $$('.moderation-generation-button');
    if (buttons.some(button => button.disabled)) return;
    const labels = new Map(buttons.map(button => [button, button.innerHTML]));
    buttons.forEach(button => setBusy(button, true));
    const activeButton = buttons.find(button => button.dataset.generationTarget === target);
    if (activeButton) activeButton.textContent = 'Генерируем…';
    try {
      const rows = await api('/admin/scenario-candidates/generate', { method: 'POST', body: JSON.stringify({ target, model: selectedModel }) });
      const rejected = rows.filter(item => item.status === 'AUTO_REJECTED').length;
      moderationStatus = 'PENDING_REVIEW';
      $$('.moderation-toolbar button').forEach(item => item.classList.toggle('is-active', item.dataset.moderationStatus === moderationStatus));
      showToast(rejected ? 'Кейс отправлен в автоотказ' : 'Кейс добавлен в очередь');
      await loadModeration();
    } catch (error) { showToast(error.message); }
    finally {
      buttons.forEach(button => {
        setBusy(button, false);
        button.innerHTML = labels.get(button);
      });
    }
  }

  function bindModeration() {
    $('#clear-practice-cycles').addEventListener('click', requestClearPracticeCycles);
    $('#clear-practice-form').addEventListener('submit', confirmClearPracticeCycles);
    $('#clear-practice-cancel').addEventListener('click', () => $('#clear-practice-dialog').close());
    $$('.moderation-generation-button').forEach(button => button.addEventListener('click', () => {
      generateCandidates(button.dataset.generationTarget);
    }));
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
    bindModelPickers();
    setRoute(location.hash.slice(1) || 'theory', false);
    await Promise.allSettled([loadCurriculum(), refreshProgressView(), loadSystemStatus()]);
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
