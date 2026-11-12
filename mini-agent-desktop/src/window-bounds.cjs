const { performance } = require('node:perf_hooks');

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

function finiteNumber(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

/** Saved preferences are untrusted JSON; Electron rectangles require finite int32 values. */
function restoreWindowBounds(saved, workArea) {
  const area = validateBounds(workArea);
  saved = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
  const width = Math.min(area.width, Math.max(800, Math.round(finiteNumber(saved.width, 1200))));
  const height = Math.min(area.height, Math.max(600, Math.round(finiteNumber(saved.height, 800))));
  const centeredX = area.x + Math.round((area.width - width) / 2);
  const centeredY = area.y + Math.round((area.height - height) / 2);
  const x = Math.max(area.x, Math.min(area.x + area.width - width,
    Math.round(finiteNumber(saved.x, centeredX))));
  const y = Math.max(area.y, Math.min(area.y + area.height - height,
    Math.round(finiteNumber(saved.y, centeredY))));
  return validateBounds({ x, y, width, height });
}

function validateBounds(bounds) {
  if (!bounds || typeof bounds !== 'object') throw new TypeError('Window bounds must be a rectangle');
  const result = {};
  for (const key of ['x', 'y', 'width', 'height']) {
    const value = bounds[key];
    if (!Number.isInteger(value) || value < INT_MIN || value > INT_MAX
        || ((key === 'width' || key === 'height') && value <= 0)) {
      throw new TypeError(`Invalid window ${key}: expected a finite int32${key === 'width' || key === 'height' ? ' > 0' : ''}`);
    }
    result[key] = value;
  }
  return result;
}

/** All timer failures reject the awaited promise; none escape as main-process exceptions. */
function animateWindowBounds(window, target, durationMs) {
  return new Promise((resolve, reject) => {
    if (!window || window.isDestroyed()) { resolve(); return; }
    let from;
    let to;
    try {
      from = validateBounds(window.getBounds());
      to = validateBounds(target);
      if (!Number.isFinite(durationMs) || durationMs < 0) throw new TypeError('Invalid animation duration');
    } catch (error) { reject(error); return; }
    const startedAt = performance.now();
    let timer;
    const settle = (error) => {
      clearInterval(timer);
      if (error) reject(error); else resolve();
    };
    const tick = () => {
      try {
        if (window.isDestroyed()) { settle(); return; }
        const progress = durationMs === 0 ? 1 : Math.max(0, Math.min(1, (performance.now() - startedAt) / durationMs));
        const eased = 1 - Math.pow(1 - progress, 3);
        const rectangle = {};
        for (const key of ['x', 'y', 'width', 'height']) {
          rectangle[key] = Math.round(from[key] + (to[key] - from[key]) * eased);
        }
        window.setBounds(validateBounds(rectangle));
        if (progress >= 1) settle();
      } catch (error) { settle(error); }
    };
    if (durationMs === 0) tick();
    else timer = setInterval(tick, 16);
  });
}

module.exports = { restoreWindowBounds, validateBounds, animateWindowBounds };
