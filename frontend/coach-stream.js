(function exposeCoachStream(root, factory) {
  'use strict';

  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && typeof root === 'object') root.QH_COACH_STREAM = api;
})(typeof globalThis === 'object' ? globalThis : this, () => {
  'use strict';

  function valid(payload) {
    return payload != null
      && Number.isSafeInteger(payload.version)
      && payload.version >= 0
      && typeof payload.text === 'string';
  }

  function createCoachStream(options) {
    if (!options || typeof options.render !== 'function') {
      throw new TypeError('render callback is required');
    }

    const render = options.render;
    const schedule = options.schedule || ((callback, delay) => setTimeout(callback, delay));
    const cancel = options.cancel || (handle => clearTimeout(handle));
    const delay = options.delay ?? 60;
    let acceptedVersion = -1;
    let latestText = '';
    let pending = null;
    let finished = false;

    function accept(payload) {
      if (finished || !valid(payload) || payload.version <= acceptedVersion) return false;
      acceptedVersion = payload.version;
      latestText = payload.text;
      if (pending === null) {
        pending = schedule(() => {
          pending = null;
          if (!finished) render(latestText);
        }, delay);
      }
      return true;
    }

    function finish(payload) {
      if (finished || !valid(payload)) return false;
      finished = true;
      acceptedVersion = Math.max(acceptedVersion, payload.version);
      latestText = payload.text;
      if (pending !== null) cancel(pending);
      pending = null;
      render(latestText);
      return true;
    }

    function dispose() {
      finished = true;
      if (pending !== null) cancel(pending);
      pending = null;
    }

    return Object.freeze({
      accept,
      finish,
      text: () => latestText,
      dispose
    });
  }

  return Object.freeze({ createCoachStream });
});
