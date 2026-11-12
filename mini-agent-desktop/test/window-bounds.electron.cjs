// Run via node_modules/electron/dist/electron.exe; uses only a hidden disposable window.
const { app, BrowserWindow, screen } = require('electron');
const assert = require('node:assert/strict');
const path = require('node:path');
const { restoreWindowBounds, animateWindowBounds } = require('../src/window-bounds.cjs');
app.setPath('userData', path.join(app.getPath('temp'), `miniagent-bounds-check-${process.pid}`));
app.whenReady().then(async () => {
  const window = new BrowserWindow({ show: false, width: 520, height: 400,
    webPreferences: { contextIsolation: true, nodeIntegration: false } });
  const area = screen.getPrimaryDisplay().workArea;
  const original = window.getBounds();
  let reproducedOriginalTypeError = false;
  try { window.setBounds({ ...original, width: NaN }); }
  catch (error) { reproducedOriginalTypeError = error instanceof TypeError && /conversion|argument/i.test(error.message); }
  assert.ok(reproducedOriginalTypeError, 'real Electron must reproduce the reported conversion error for a NaN rectangle');
  let animations = 0;
  for (const saved of [{ x: -7, y: -7, width: 1440, height: 852 },
    { width: 'bad', height: null }, { width: 900.5, height: 650.8, x: 1e30 }, null]) {
    const target = restoreWindowBounds(saved, area);
    await animateWindowBounds(window, target, 70);
    const actual = window.getBounds();
    assert.equal(actual.width, target.width);
    assert.equal(actual.height, target.height);
    animations++;
  }
  window.destroy();
  console.log(JSON.stringify({ electron: process.versions.electron, reproducedOriginalTypeError, animations,
    workArea: area, successful: true }));
  app.exit(0);
}).catch(error => { console.error(error.stack || String(error)); app.exit(1); });
