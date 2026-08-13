import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const curriculumUrl = new URL('../../backend/src/main/resources/curriculum/categories.json', import.meta.url);

async function curriculum() {
  return JSON.parse(await fs.readFile(curriculumUrl, 'utf8'));
}

test('every category contains a complete applied-theory lesson', async () => {
  const document = await curriculum();
  const sourceIds = new Set(document.sources.map(source => source.key));

  assert.equal(document.categories.length, 7);
  for (const category of document.categories) {
    assert.ok(category.workedExample, `${category.code} lacks workedExample`);
    assert.ok(category.workedExample.title.trim(), `${category.code} lacks a worked-example title`);
    assert.ok(category.workedExample.situation.trim(), `${category.code} lacks a situation`);
    assert.ok(category.workedExample.ordinaryQuestion.trim(), `${category.code} lacks an ordinary question`);
    assert.ok(category.workedExample.hackerQuestion.trim(), `${category.code} lacks a hacker question`);
    assert.ok(category.workedExample.solution.trim(), `${category.code} lacks a solution`);
    assert.ok(category.workedExample.whyItFits.trim(), `${category.code} lacks classification reasoning`);
    assert.ok(category.workedExample.reasoningSteps.length >= 3, `${category.code} has too few reasoning steps`);
    assert.ok(category.workedExample.reasoningSteps.length <= 5, `${category.code} has too many reasoning steps`);
    assert.equal(category.questionTemplates.length, 5, `${category.code} should preserve five templates`);
    assert.ok(category.quickExercise.trim(), `${category.code} lacks a quick exercise`);
    assert.ok(category.experiment.trim(), `${category.code} lacks a field experiment`);
    assert.equal(category.cases.length, 3, `${category.code} should preserve three historical cases`);
    assert.ok(category.cases.every(item => item.sourceIds.length >= 1), `${category.code} case lacks sources`);
    assert.ok(category.cases.flatMap(item => item.sourceIds).every(id => sourceIds.has(id)), `${category.code} references an unknown source`);
  }
});

test('worked examples expose each technique complete reasoning operation', async () => {
  const document = await curriculum();
  const byCode = new Map(document.categories.map(category => [category.code, category]));
  const labels = code => byCode.get(code).workedExample.reasoningSteps.map(step => step.label);

  assert.deepEqual(labels('INVERSION'), ['Нежелательный исход', 'Причины', 'Существующий сигнал', 'Защитное действие']);
  assert.deepEqual(labels('HYPERBOLE'), ['Изменённый параметр', 'Что сломалось', 'Новый механизм', 'Реальные ограничения']);
  assert.deepEqual(labels('CROSS_DISCIPLINE'), ['Элемент источника', 'Эквивалент в задаче', 'Существенное различие', 'Проверяемый прогноз']);
  assert.deepEqual(labels('BACKCASTING'), ['2030', '2028', '2027', 'Сегодня']);
  assert.deepEqual(labels('PROVOCATION'), ['Отменённое правило', 'Защищаемая функция', 'Инварианты', 'Альтернативный механизм', 'Пилот и kill switch']);
  assert.deepEqual(labels('REFRAMING'), ['Исходный симптом', 'Новый outcome', 'Новое evidence', 'Новые решения']);
  assert.deepEqual(labels('SIMPLIFICATION'), ['Проверенный факт', 'Ограничение', 'Защитный механизм', 'Историческая привычка', 'Минимальная пересборка']);

  assert.match(byCode.get('INVERSION').workedExample.solution, /защит|сигнал/i);
  assert.match(byCode.get('HYPERBOLE').workedExample.reasoningSteps[2].text, /маршрут|самообслуж|механизм/i);
  assert.match(byCode.get('BACKCASTING').workedExample.hackerQuestion, /2030/);
  assert.match(byCode.get('PROVOCATION').workedExample.reasoningSteps.at(-1).text, /kill switch/i);
  assert.match(byCode.get('SIMPLIFICATION').workedExample.reasoningSteps.at(-1).text, /исключен|откат|rollback/i);
});
