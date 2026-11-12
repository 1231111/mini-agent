const test = require('node:test');
const assert = require('node:assert/strict');
const { restoreWindowBounds, animateWindowBounds } = require('../src/window-bounds.cjs');

const area = { x: 0, y: 0, width: 1440, height: 850 };

test('damaged JSON dimensions cannot produce NaN or overflow in an Electron rectangle', () => {
  for (const preferences of [null, [], 'bad', { width: 'bad', height: {} },
    { x: NaN, y: Infinity, width: Infinity, height: -Infinity },
    { x: -1e30, y: 1e30, width: 1e30, height: 1e30 }]) {
    const bounds = restoreWindowBounds(preferences, area);
    for (const value of Object.values(bounds)) assert.ok(Number.isInteger(value));
    assert.ok(bounds.width > 0 && bounds.width <= area.width);
    assert.ok(bounds.height > 0 && bounds.height <= area.height);
    assert.ok(bounds.x >= area.x && bounds.x + bounds.width <= area.width);
    assert.ok(bounds.y >= area.y && bounds.y + bounds.height <= area.height);
  }
});

test('restore keeps usable saved geometry and adapts when its display has disappeared', () => {
  assert.deepEqual(restoreWindowBounds({ x: 50, y: 30, width: 1100, height: 700 }, area),
    { x: 50, y: 30, width: 1100, height: 700 });
  assert.deepEqual(restoreWindowBounds({ x: 4000, y: 2400, width: 1200, height: 800 },
    { x: -640, y: 0, width: 640, height: 480 }), { x: -640, y: 0, width: 640, height: 480 });
});

test('async native setBounds failure rejects its awaiter and stops the timer', async () => {
  let nativeCalls = 0;
  const window = { isDestroyed: () => false, getBounds: () => ({ x: 0, y: 0, width: 520, height: 400 }),
    setBounds: () => { nativeCalls++; throw new TypeError('native conversion failure'); } };
  await assert.rejects(animateWindowBounds(window, { x: 100, y: 100, width: 1200, height: 800 }, 60), /native conversion/);
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(nativeCalls, 1, 'failed animation must not keep firing or throw outside the promise');
});

test('animation rejects incomplete or nonnumeric rectangles before calling native APIs', async () => {
  const window = { isDestroyed: () => false, getBounds: () => ({ x: 0, y: 0, width: 520, height: 400 }),
    setBounds: () => assert.fail('must not pass corrupt preferences to Electron') };
  await assert.rejects(animateWindowBounds(window, { x: 0, y: 0, width: NaN, height: 800 }, 60), /Invalid window width/);
});

test('destroying a window during animation safely resolves without accessing its native peer', async () => {
  let destroyed = false;
  let calls = 0;
  const window = { isDestroyed: () => destroyed, getBounds: () => ({ x: 0, y: 0, width: 520, height: 400 }),
    setBounds: () => { calls++; destroyed = true; } };
  await animateWindowBounds(window, { x: 50, y: 50, width: 1000, height: 700 }, 100);
  assert.equal(calls, 1);
});
