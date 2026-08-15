(function exposePracticeRetry(root, factory) {
  'use strict';

  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && typeof root === 'object') root.QH_PRACTICE_RETRY = api;
})(typeof globalThis === 'object' ? globalThis : this, () => {
  'use strict';

  function createRetrySubmitter(options) {
    if (!options || typeof options.request !== 'function') {
      throw new TypeError('request callback is required');
    }
    if (typeof options.keyFactory !== 'function') {
      throw new TypeError('keyFactory callback is required');
    }

    let pending = null;

    async function submit({ attemptId, values, model }) {
      if (!attemptId) throw new TypeError('attemptId is required');
      if (!pending) {
        pending = {
          attemptId,
          body: JSON.stringify({
            ...values,
            model,
            idempotencyKey: options.keyFactory('retry')
          })
        };
      }
      if (pending.attemptId !== attemptId) {
        throw new Error('Незавершённая повторная отправка относится к другой попытке');
      }

      let result;
      try {
        result = await options.request(
          `/practice/attempts/${pending.attemptId}/retries`,
          { method: 'POST', body: pending.body }
        );
      } catch (error) {
        if (Number.isInteger(error?.status) && error.status >= 400 && error.status < 500) {
          pending = null;
        }
        throw error;
      }
      pending = null;
      return result;
    }

    return Object.freeze({
      submit,
      reset() { pending = null; }
    });
  }

  return Object.freeze({ createRetrySubmitter });
});
