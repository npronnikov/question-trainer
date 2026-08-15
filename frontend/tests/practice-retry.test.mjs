import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createRetrySubmitter } = require('../practice-retry.js');

test('an ambiguous retry failure reuses the same key and payload', async () => {
  const calls = [];
  let keyNumber = 0;
  const submitter = createRetrySubmitter({
    keyFactory: prefix => `${prefix}-${++keyNumber}`,
    request: async (path, options) => {
      calls.push({ path, options });
      if (calls.length === 1) throw new TypeError('connection lost');
      return { attemptId: 'child-attempt' };
    }
  });
  const firstValues = {
    question: 'Вопрос', rationale: 'Обоснование', solution: 'Решение'
  };

  await assert.rejects(
    submitter.submit({ attemptId: 'parent-attempt', values: firstValues, model: 'model-a' }),
    /connection lost/
  );
  const firstBody = calls[0].options.body;
  assert.equal(JSON.parse(firstBody).idempotencyKey, 'retry-1');

  const result = await submitter.submit({
    attemptId: 'parent-attempt',
    values: { ...firstValues, question: 'Изменённый после потери ответа вопрос' },
    model: 'model-b'
  });

  assert.deepEqual(result, { attemptId: 'child-attempt' });
  assert.equal(calls[1].path, '/practice/attempts/parent-attempt/retries');
  assert.equal(calls[1].options.body, firstBody);
  assert.equal(keyNumber, 1);
});

test('a completed logical retry gets a new idempotency key', async () => {
  const bodies = [];
  let keyNumber = 0;
  const submitter = createRetrySubmitter({
    keyFactory: prefix => `${prefix}-${++keyNumber}`,
    request: async (_path, options) => {
      bodies.push(JSON.parse(options.body));
      return { attemptId: `child-${bodies.length}` };
    }
  });
  const values = { question: 'Q', rationale: 'R', solution: 'S' };

  await submitter.submit({ attemptId: 'parent-1', values, model: 'model-a' });
  await submitter.submit({ attemptId: 'parent-2', values, model: 'model-a' });

  assert.deepEqual(bodies.map(body => body.idempotencyKey), ['retry-1', 'retry-2']);
});

test('a definite client rejection allows corrected input with a new key', async () => {
  const bodies = [];
  let keyNumber = 0;
  const submitter = createRetrySubmitter({
    keyFactory: prefix => `${prefix}-${++keyNumber}`,
    request: async (_path, options) => {
      bodies.push(JSON.parse(options.body));
      if (bodies.length === 1) {
        throw Object.assign(new Error('invalid input'), { status: 400 });
      }
      return { attemptId: 'corrected-child' };
    }
  });
  const values = { question: 'Q', rationale: 'R', solution: 'S' };

  await assert.rejects(
    submitter.submit({ attemptId: 'parent', values, model: 'model-a' }),
    /invalid input/
  );
  await submitter.submit({
    attemptId: 'parent', values: { ...values, question: 'Corrected Q' }, model: 'model-a'
  });

  assert.deepEqual(bodies.map(body => body.idempotencyKey), ['retry-1', 'retry-2']);
  assert.equal(bodies[1].question, 'Corrected Q');
});
