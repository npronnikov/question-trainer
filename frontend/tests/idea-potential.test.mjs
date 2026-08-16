import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  DIMENSIONS,
  metricValue,
  radarMarkup,
  trendMarkup
} = require('../idea-potential.js');

const completePotential = {
  overallScore: 3,
  complete: true,
  dimensions: [
    { name: 'impact', status: 'SCORED', score: 4, evidence: 'Меняет результат' },
    { name: 'questionAlignment', status: 'SCORED', score: 3, evidence: 'Отвечает на вопрос' },
    { name: 'disruption', status: 'SCORED', score: 2, evidence: 'Выходит за очевидное' },
    { name: 'feasibility', status: 'SCORED', score: 3, evidence: 'Можно проверить' }
  ]
};

test('idea dimensions keep the approved order and Russian labels', () => {
  assert.deepEqual(DIMENSIONS.map(item => item.key), [
    'impact', 'questionAlignment', 'disruption', 'feasibility'
  ]);
  assert.deepEqual(DIMENSIONS.map(item => item.label), [
    'Сила идеи', 'Связь с вопросом', 'Дизрапт', 'Реализуемость'
  ]);
});

test('radar renders four numeric axes, evidence, and deterministic overall', () => {
  const markup = radarMarkup(completePotential, { title: 'Попытка 1' });

  assert.match(markup, /<svg[^>]+viewBox="0 0 420 360"/);
  assert.match(markup, /Потенциал идеи: 3\.0 из 4/);
  for (const dimension of DIMENSIONS) {
    assert.match(markup, new RegExp(dimension.label));
  }
  assert.equal((markup.match(/data-score="[0-4]"/g) || []).length, 4);
  assert.match(markup, /Меняет результат/);
});

test('missing feasibility is a real gap, never a zero score', () => {
  const incomplete = structuredClone(completePotential);
  incomplete.complete = false;
  incomplete.overallScore = null;
  incomplete.dimensions[3] = {
    name: 'feasibility', status: 'INSUFFICIENT_CONTEXT', score: null,
    evidence: 'В кейсе не заданы ресурсы'
  };

  const markup = radarMarkup(incomplete);

  assert.match(markup, /Недостаточно данных/);
  assert.doesNotMatch(markup, /Потенциал идеи: 0\.0/);
  assert.equal((markup.match(/data-score="[0-4]"/g) || []).length, 3);
});

test('metric values come only from server-provided potential', () => {
  const point = { ideaPotential: completePotential };

  assert.equal(metricValue(point, 'overall'), 3);
  assert.equal(metricValue(point, 'impact'), 4);
  assert.equal(metricValue({ gapReason: 'LEGACY_SCHEMA' }, 'overall'), null);
  assert.equal(metricValue({
    gapReason: 'INCOMPLETE_PROFILE',
    ideaPotential: { ...completePotential, complete: false, overallScore: null }
  }, 'overall'), null);
  assert.equal(metricValue({ ideaPotential: { ...completePotential, overallScore: '' } }, 'overall'), null);
});

test('incomplete overall never creates a zero point in the trend', () => {
  const category = {
    code: 'INVERSION', name: 'Инверсия', points: [{
      cycleNumber: 1,
      assignmentId: 'a1',
      attemptId: 't1',
      gapReason: 'INCOMPLETE_PROFILE',
      ideaPotential: { ...completePotential, complete: false, overallScore: null }
    }]
  };

  const markup = trendMarkup(category, 'overall');

  assert.match(markup, /data-segments="0"/);
  assert.doesNotMatch(markup, /class="idea-trend-point"/);
  assert.doesNotMatch(markup, />0\.0<\/text>/);
});

test('trend splits paths at gaps and exposes keyboard points', () => {
  const category = {
    code: 'INVERSION', name: 'Инверсия', points: [
      { cycleNumber: 1, assignmentId: 'a1', attemptId: 't1', completedAt: '2026-08-01T10:00:00Z', ideaPotential: completePotential },
      { cycleNumber: 2, assignmentId: 'a2', gapReason: 'CYCLE_INCOMPLETE' },
      { cycleNumber: 3, assignmentId: 'a3', attemptId: 't3', completedAt: '2026-08-15T10:00:00Z', ideaPotential: { ...completePotential, overallScore: 4 } }
    ]
  };

  const markup = trendMarkup(category, 'overall');

  assert.match(markup, /data-segments="2"/);
  assert.equal((markup.match(/class="idea-trend-line"/g) || []).length, 2);
  assert.equal((markup.match(/tabindex="0"/g) || []).length, 2);
  assert.match(markup, /role="button"/);
  assert.match(markup, /Инверсия, цикл 1, Общий потенциал: 3\.0 из 4/);
});

test('visualization escapes labels and evidence before writing SVG or HTML', () => {
  const unsafe = structuredClone(completePotential);
  unsafe.dimensions[0].evidence = '<script>alert(1)</script>';
  const category = {
    code: 'INVERSION', name: '<img src=x onerror=alert(1)>',
    points: [{ cycleNumber: 1, assignmentId: '" onclick="alert(1)', attemptId: 't', ideaPotential: unsafe }]
  };

  assert.doesNotMatch(radarMarkup(unsafe), /<script>/);
  assert.doesNotMatch(trendMarkup(category, 'impact'), /<img/);
  assert.doesNotMatch(trendMarkup(category, 'impact'), /onclick=/);
});
