(function exposeIdeaPotential(root, factory) {
  'use strict';

  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && typeof root === 'object') root.QH_IDEA_POTENTIAL = api;
})(typeof globalThis === 'object' ? globalThis : this, () => {
  'use strict';

  const DIMENSIONS = Object.freeze([
    Object.freeze({ key: 'impact', label: 'Сила идеи', shortLabel: 'Сила' }),
    Object.freeze({ key: 'questionAlignment', label: 'Связь с вопросом', shortLabel: 'Связь' }),
    Object.freeze({ key: 'disruption', label: 'Дизрапт', shortLabel: 'Дизрапт' }),
    Object.freeze({ key: 'feasibility', label: 'Реализуемость', shortLabel: 'Реализация' })
  ]);
  const METRICS = Object.freeze([
    Object.freeze({ key: 'overall', label: 'Общий потенциал' }),
    ...DIMENSIONS
  ]);

  function escapeXml(value) {
    return String(value == null ? '' : value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function safeIdentifier(value) {
    return String(value == null ? '' : value).replace(/[^a-zA-Z0-9_-]/g, '-');
  }

  function score(value) {
    if (value == null || typeof value === 'boolean'
      || typeof value === 'string' && !value.trim()) return null;
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 && number <= 4 ? number : null;
  }

  function dimensionMap(potential) {
    return new Map((potential?.dimensions || []).map(item => [item?.name, item]));
  }

  function metricValue(point, metric) {
    if (!point?.ideaPotential || point.gapReason && point.gapReason !== 'INCOMPLETE_PROFILE') {
      return null;
    }
    if (metric === 'overall') return score(point.ideaPotential.overallScore);
    const item = dimensionMap(point.ideaPotential).get(metric);
    return item?.status === 'SCORED' ? score(item.score) : null;
  }

  function radarPoint(index, value, centerX = 210, centerY = 150, radius = 96) {
    const angle = (-Math.PI / 2) + (index * Math.PI / 2);
    const scaled = radius * (value / 4);
    return [
      Number((centerX + Math.cos(angle) * scaled).toFixed(2)),
      Number((centerY + Math.sin(angle) * scaled).toFixed(2))
    ];
  }

  function polygonPoints(level) {
    return DIMENSIONS.map((_, index) => radarPoint(index, level).join(',')).join(' ');
  }

  function radarMarkup(potential, options = {}) {
    const values = dimensionMap(potential);
    const title = escapeXml(options.title || 'Потенциал идеи');
    const complete = potential?.complete === true && score(potential?.overallScore) != null;
    const overall = complete ? score(potential.overallScore).toFixed(1) : null;
    const aria = complete
      ? `${title}. Потенциал идеи: ${overall} из 4`
      : `${title}. Профиль неполный: недостаточно данных`;
    const scored = DIMENSIONS.map((dimension, index) => {
      const item = values.get(dimension.key);
      const value = item?.status === 'SCORED' ? score(item.score) : null;
      return { dimension, item, index, value };
    });
    const completeShape = scored.every(item => item.value != null);
    const shape = completeShape
      ? `<polygon class="idea-radar-shape" points="${scored.map(item => radarPoint(item.index, item.value).join(',')).join(' ')}"></polygon>`
      : `<polyline class="idea-radar-shape is-incomplete" points="${scored.filter(item => item.value != null).map(item => radarPoint(item.index, item.value).join(',')).join(' ')}"></polyline>`;
    const labels = [
      { x: 210, y: 22, anchor: 'middle' },
      { x: 326, y: 154, anchor: 'start' },
      { x: 210, y: 286, anchor: 'middle' },
      { x: 94, y: 154, anchor: 'end' }
    ];
    const axes = scored.map(item => {
      const outer = radarPoint(item.index, 4);
      const label = labels[item.index];
      const display = item.value == null ? 'Недостаточно данных' : `${item.value}/4`;
      const scoreAttribute = item.value == null ? '' : ` data-score="${item.value}"`;
      return `<line class="idea-radar-axis" x1="210" y1="150" x2="${outer[0]}" y2="${outer[1]}"></line>
        <text class="idea-radar-label" x="${label.x}" y="${label.y}" text-anchor="${label.anchor}"${scoreAttribute}>
          <tspan x="${label.x}">${escapeXml(item.dimension.shortLabel)}</tspan>
          <tspan class="idea-radar-value" x="${label.x}" dy="16">${escapeXml(display)}</tspan>
        </text>`;
    }).join('');
    const evidence = scored.map(item => `<li>
      <span>${escapeXml(item.dimension.label)}</span>
      <strong>${item.value == null ? 'Недостаточно данных' : `${item.value}/4`}</strong>
      <p>${escapeXml(item.item?.evidence || 'Доказательство не сохранено')}</p>
    </li>`).join('');

    return `<section class="idea-radar" aria-label="${escapeXml(aria)}">
      <div class="idea-radar-visual">
        <svg viewBox="0 0 420 360" role="img" aria-label="${escapeXml(aria)}">
          <title>${escapeXml(aria)}</title>
          <g class="idea-radar-grid">
            ${[1, 2, 3, 4].map(level => `<polygon points="${polygonPoints(level)}"></polygon>`).join('')}
          </g>
          ${axes}
          ${shape}
        </svg>
        <p class="idea-radar-overall">${complete
          ? `Потенциал идеи <strong>${overall}/4</strong>`
          : '<strong>Общий балл не рассчитан</strong><span>Недостаточно данных</span>'}</p>
      </div>
      <ol class="idea-evidence-list">${evidence}</ol>
    </section>`;
  }

  function metricLabel(metric) {
    return METRICS.find(item => item.key === metric)?.label || metric;
  }

  function pointX(cycle, maxCycle) {
    if (maxCycle <= 1) return 340;
    return 64 + ((cycle - 1) / (maxCycle - 1)) * 576;
  }

  function pointY(value) {
    return 250 - (value / 4) * 208;
  }

  function segments(points, metric) {
    const result = [];
    let current = [];
    for (const point of points) {
      const value = metricValue(point, metric);
      if (value == null) {
        if (current.length) result.push(current);
        current = [];
      } else {
        current.push({ point, value });
      }
    }
    if (current.length) result.push(current);
    return result;
  }

  function trendMarkup(category, metric = 'overall') {
    const points = Array.isArray(category?.points) ? category.points : [];
    const maxCycle = Math.max(1, ...points.map(point => Number(point.cycleNumber) || 1));
    const lineSegments = segments(points, metric);
    const label = metricLabel(metric);
    const paths = lineSegments.map(segment => `<path class="idea-trend-line" d="${segment.map((item, index) => {
      const x = pointX(item.point.cycleNumber, maxCycle).toFixed(2);
      const y = pointY(item.value).toFixed(2);
      return `${index ? 'L' : 'M'} ${x} ${y}`;
    }).join(' ')}"></path>`).join('');
    const pointMarkup = points.map(point => {
      const value = metricValue(point, metric);
      if (value == null) return '';
      const x = pointX(point.cycleNumber, maxCycle).toFixed(2);
      const y = pointY(value).toFixed(2);
      const date = point.completedAt ? `, ${String(point.completedAt).slice(0, 10)}` : '';
      const aria = `${category?.name || category?.code || 'Категория'}, цикл ${point.cycleNumber}, ${label}: ${value.toFixed(1)} из 4${date}`;
      return `<g class="idea-trend-point" tabindex="0" role="button"
        aria-label="${escapeXml(aria)}"
        data-assignment-id="${safeIdentifier(point.assignmentId)}"
        data-attempt-id="${safeIdentifier(point.attemptId)}"
        data-cycle-number="${Number(point.cycleNumber) || 0}">
        <circle cx="${x}" cy="${y}" r="7"></circle>
        <text x="${x}" y="${Number(y) - 14}" text-anchor="middle">${value.toFixed(1)}</text>
      </g>`;
    }).join('');
    const cycleLabels = points.map(point => {
      const x = pointX(point.cycleNumber, maxCycle).toFixed(2);
      return `<text class="idea-trend-cycle" x="${x}" y="282" text-anchor="middle">Цикл ${Number(point.cycleNumber) || 0}</text>`;
    }).join('');
    const grid = [0, 1, 2, 3, 4].map(value => {
      const y = pointY(value).toFixed(2);
      return `<line x1="64" y1="${y}" x2="640" y2="${y}"></line><text x="48" y="${Number(y) + 4}" text-anchor="end">${value}</text>`;
    }).join('');

    return `<svg class="idea-trend" viewBox="0 0 680 320" role="img"
      aria-label="${escapeXml(`${category?.name || 'Категория'}: ${label} по циклам`)}"
      data-segments="${lineSegments.length}">
      <title>${escapeXml(`${category?.name || 'Категория'}: ${label} по циклам`)}</title>
      <g class="idea-trend-grid">${grid}</g>
      ${paths}${pointMarkup}${cycleLabels}
    </svg>`;
  }

  return Object.freeze({
    DIMENSIONS,
    METRICS,
    metricValue,
    radarMarkup,
    trendMarkup
  });
});
