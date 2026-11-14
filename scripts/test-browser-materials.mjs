import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { driverPackagePrefix, readBrowserRequirements, validateBrowserMaterials } from './lib/browser-materials.mjs';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-browser-materials-'));
const pkg = path.join(root, 'package'), browsers = path.join(root, 'browsers');
function write(file, text) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, text); }
function binary(file, platform, bytes = 11 * 1024 * 1024) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const fd = fs.openSync(file, 'w');
  try {
    fs.ftruncateSync(fd, bytes);
    const magic = platform === 'win32' ? Buffer.from('MZ00') : Buffer.from(platform === 'darwin' ? 'cffaedfe' : '7f454c46', 'hex');
    fs.writeSync(fd, magic, 0, magic.length, 0);
  } finally { fs.closeSync(fd); }
  fs.chmodSync(file, 0o755);
}
function material(item) {
  binary(item.executable, item.platform);
  if (item.platform === 'win32' && item.name === 'chromium') binary(path.join(path.dirname(item.executable), 'chrome.dll'), item.platform);
  write(item.marker, '');
}
try {
  write(path.join(pkg, 'browsers.json'), JSON.stringify({ browsers: [
    { name: 'chromium-headless-shell', revision: '1217', revisionOverrides: { fixture: '1220' } },
    { name: 'chromium', revision: '1217' }] }));
  write(path.join(pkg, 'lib/utils/hostPlatform.js'), 'exports.hostPlatform="fixture";');
  write(path.join(pkg, 'lib/server/registry/index.js'), `
    const path=require('path'), fs=require('fs');
    const manifest=JSON.parse(fs.readFileSync(path.join(__dirname,'../../../browsers.json')));
    exports.browserDirectoryToMarkerFilePath=directory=>path.join(directory,'INSTALLATION_COMPLETE');
    exports.registry={findExecutable(name){
      const item=manifest.browsers.find(b=>b.name===name), override=item.revisionOverrides?.fixture;
      const directory=path.join(process.env.PLAYWRIGHT_BROWSERS_PATH,
        (override?name+'_fixture_special':name).replace(/-/g,'_')+'-'+(override||item.revision));
      return {directory,executablePath:()=>path.join(directory,
        name==='chromium'?'custom-headed-layout/chrome.exe':'new-driver-layout/shell.exe')};
    }};`);
  const headless = readBrowserRequirements(pkg, browsers);
  assert.equal(headless.length, 1);
  assert.equal(headless[0].revision, '1220');
  assert.match(headless[0].executable, /new-driver-layout/);
  assert.match(headless[0].directory, /chromium_headless_shell_fixture_special-1220$/);
  console.log('PASS: reads bundled manifest override and driver executable layout without pinned revision');

  const old = { ...headless[0], revision: '1148', directory: path.join(browsers, 'chromium_headless_shell-1148'),
    executable: path.join(browsers, 'chromium_headless_shell-1148/old-shell.exe'), marker: path.join(browsers, 'chromium_headless_shell-1148/INSTALLATION_COMPLETE') };
  material(old);
  assert.throws(() => validateBrowserMaterials(headless, browsers), /revision 1220[\s\S]*去掉 --skip-browsers/);
  assert.ok(fs.existsSync(old.executable));
  console.log('PASS: old revision cannot satisfy skip and remains untouched');

  material(headless[0]);
  assert.equal(validateBrowserMaterials(headless, browsers), headless);
  console.log('PASS: exact revision with installation marker and complete main binary can be reused');

  fs.unlinkSync(headless[0].marker);
  assert.throws(() => validateBrowserMaterials(headless, browsers), /不完整/);
  write(headless[0].marker, '');
  binary(headless[0].executable, headless[0].platform, 4096);
  assert.throws(() => validateBrowserMaterials(headless, browsers), /过小/);
  material(headless[0]);
  const fd = fs.openSync(headless[0].executable, 'r+');
  try { fs.writeSync(fd, Buffer.from('oops'), 0, 4, 0); } finally { fs.closeSync(fd); }
  assert.throws(() => validateBrowserMaterials(headless, browsers), /程序/);
  material(headless[0]);
  console.log('PASS: incomplete install, truncated executable and wrong binary format are rejected');

  const both = readBrowserRequirements(pkg, browsers, true);
  assert.equal(both.length, 2);
  assert.throws(() => validateBrowserMaterials(both, browsers), /chromium revision 1217/);
  material(both[1]); validateBrowserMaterials(both, browsers);
  if (both[1].platform === 'win32') {
    fs.unlinkSync(path.join(path.dirname(both[1].executable), 'chrome.dll'));
    assert.throws(() => validateBrowserMaterials(both, browsers), /不完整/);
  }
  console.log('PASS: headed mode additionally requires its exact revision and Windows chrome.dll');

  assert.equal(driverPackagePrefix('darwin', 'arm64'), 'driver/mac-arm64/package/');
  assert.equal(driverPackagePrefix('linux', 'arm64'), 'driver/linux-arm64/package/');
  assert.equal(driverPackagePrefix('win32', 'x64'), 'driver/win32_x64/package/');
  assert.throws(() => driverPackagePrefix('win32', 'arm64'), /不支持/);
  console.log('PASS: extracts only the current supported platform package');

  const build = fs.readFileSync(new URL('./build-desktop.mjs', import.meta.url), 'utf8');
  const batch = fs.readFileSync(new URL('../mini-agent-desktop/package-win.bat', import.meta.url), 'utf8');
  assert.doesNotMatch(batch, /BUILD_ARGS=.*--skip-browsers/);
  assert.doesNotMatch(build, /rmrf\(BROWSERS_DIR\)/);
  assert.match(build, /PLAYWRIGHT_SKIP_BROWSER_GC: '1'/);
  assert.match(build, /function stepPackage\(skip, targets, browserRequirements\)/);
  assert.match(build, /validateBrowserMaterials\(browserRequirements, BROWSERS_DIR\)/);
  console.log('PASS: one-click build avoids stale auto-skip and final material check is wired without deleting old browsers');
} finally {
  // Fixture ownership is explicit; never remove browser material in the real workspace.
  const resolved = fs.realpathSync(root), parent = fs.realpathSync(os.tmpdir());
  assert.equal(path.dirname(resolved), parent);
  assert.ok(path.basename(resolved).startsWith('miniagent-browser-materials-'));
  fs.rmSync(resolved, { recursive: true, force: true });
}
