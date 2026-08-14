import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createCoachStream } = require('../coach-stream.js');

function harness() {
  const callbacks = [];
  const cancelled = [];
  const rendered = [];
  const stream = createCoachStream({
    render: text => rendered.push(text),
    schedule: callback => {
      callbacks.push(callback);
      return callbacks.length - 1;
    },
    cancel: handle => cancelled.push(handle)
  });
  return { stream, callbacks, cancelled, rendered };
}

test('new snapshots replace text and stale versions are ignored', () => {
  const { stream, callbacks, rendered } = harness();

  assert.equal(stream.accept({ version: 2, text: 'новый текст' }), true);
  assert.equal(stream.accept({ version: 1, text: 'старый текст' }), false);
  callbacks[0]();

  assert.deepEqual(rendered, ['новый текст']);
  assert.equal(stream.text(), 'новый текст');
});

test('multiple snapshots are coalesced into one render of the latest text', () => {
  const { stream, callbacks, rendered } = harness();

  stream.accept({ version: 1, text: 'А' });
  stream.accept({ version: 2, text: 'АБ' });
  stream.accept({ version: 3, text: 'АБВ' });

  assert.equal(callbacks.length, 1);
  callbacks[0]();
  assert.deepEqual(rendered, ['АБВ']);
});

test('done cancels a pending render and immediately applies authoritative text', () => {
  const { stream, cancelled, rendered } = harness();

  stream.accept({ version: 4, text: 'почти' });
  assert.equal(stream.finish({ version: 5, text: 'готово' }), true);

  assert.deepEqual(cancelled, [0]);
  assert.deepEqual(rendered, ['готово']);
  assert.equal(stream.text(), 'готово');
});

test('invalid snapshots cannot replace accepted state', () => {
  const { stream, callbacks, rendered } = harness();

  assert.equal(stream.accept({ version: 1, text: 'ответ' }), true);
  assert.equal(stream.accept({ version: 2.5, text: 'дробная версия' }), false);
  assert.equal(stream.accept({ version: 2, text: null }), false);
  assert.equal(stream.accept({ version: -1, text: 'отрицательная версия' }), false);
  callbacks[0]();

  assert.deepEqual(rendered, ['ответ']);
  assert.equal(stream.text(), 'ответ');
});
