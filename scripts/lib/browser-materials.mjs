import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const repair = '请去掉 --skip-browsers 重新备料（旧版本目录会保留）。';

export function driverPackagePrefix(platform, arch) {
  const folders = { 'win32/x64': 'win32_x64', 'darwin/x64': 'mac', 'darwin/arm64': 'mac-arm64',
    'linux/x64': 'linux', 'linux/arm64': 'linux-arm64' };
  const folder = folders[`${platform}/${arch}`];
  if (!folder) throw new Error(`Playwright 不支持此构建平台：${platform}/${arch}`);
  return `driver/${folder}/package/`;
}

/** Ask the bundled driver itself: revisions, platform overrides and executable layouts change. */
export function readBrowserRequirements(packageDir, browsersDir, withHeaded = false) {
  const probe = String.raw`
    const fs = require('fs'), path = require('path');
    const [pkg, root, headed] = process.argv.slice(1);
    const manifest = JSON.parse(fs.readFileSync(path.join(pkg, 'browsers.json'), 'utf8'));
    const mod = require(path.join(pkg, 'lib/server/registry/index.js'));
    const hostFile = ['lib/server/utils/hostPlatform.js', 'lib/utils/hostPlatform.js']
      .map(p => path.join(pkg, p)).find(p => fs.existsSync(p));
    if (!hostFile) throw new Error('driver hostPlatform module missing');
    const {hostPlatform} = require(hostFile);
    const names = headed === 'true' ? ['chromium-headless-shell', 'chromium'] : ['chromium-headless-shell'];
    const plan = names.map(name => {
      const entry = manifest.browsers.find(b => b.name === name);
      if (!entry) throw new Error('browsers.json missing ' + name);
      const override = entry.revisionOverrides?.[hostPlatform];
      const revision = String(override || entry.revision);
      if (!/^\d+$/.test(revision)) throw new Error('invalid browser revision');
      const prefix = (override ? name + '_' + hostPlatform + '_special' : name).replace(/-/g, '_');
      const expected = path.join(root, prefix + '-' + revision);
      const executable = mod.registry.findExecutable(name);
      if (!executable || executable.directory !== expected) throw new Error('driver registry/manifest disagree for ' + name);
      const main = executable.executablePath();
      if (!main) throw new Error('unsupported executable platform ' + hostPlatform);
      return {name, revision, directory:expected, executable:main, hostPlatform,
        marker:mod.browserDirectoryToMarkerFilePath(expected), platform:process.platform, arch:process.arch};
    });
    process.stdout.write(JSON.stringify(plan));
  `;
  const result = spawnSync(process.execPath, ['-e', probe, path.resolve(packageDir), path.resolve(browsersDir), String(withHeaded)], {
    encoding: 'utf8', timeout: 30_000, windowsHide: true,
    env: { ...process.env, PLAYWRIGHT_BROWSERS_PATH: path.resolve(browsersDir) }
  });
  if (result.error || result.status !== 0)
    throw new Error('无法读取 fat jar 内 Playwright 浏览器清单：' + (result.error?.message || result.stderr?.trim() || 'driver probe failed'));
  try { return JSON.parse(result.stdout); }
  catch { throw new Error('Playwright 浏览器清单响应无效'); }
}

/** This checks format and plausible size, not a full distribution checksum. */
export function validateBrowserBinary(file, platform, minimumBytes = 10 * 1024 * 1024) {
  const stat = fs.statSync(file);
  if (!stat.isFile() || stat.size < minimumBytes) throw new Error('主程序缺失、过小或不是普通文件（至少 ' + minimumBytes + ' bytes）');
  const fd = fs.openSync(file, 'r');
  try {
    const header = Buffer.alloc(4);
    if (fs.readSync(fd, header, 0, 4, 0) !== 4) throw new Error('二进制读取不完整');
    if (platform === 'win32' && header.toString('ascii', 0, 2) !== 'MZ') throw new Error('不是 Windows PE 程序');
    if (platform === 'linux' && header.toString('hex') !== '7f454c46') throw new Error('不是 Linux ELF 程序');
    if (platform === 'darwin' && !['cffaedfe', 'feedfacf', 'cafebabe', 'bebafeca'].includes(header.toString('hex')))
      throw new Error('不是 macOS Mach-O 程序');
    if (platform !== 'win32') fs.accessSync(file, fs.constants.X_OK);
  } finally { fs.closeSync(fd); }
}

export function validateBrowserMaterials(requirements, browsersDir) {
  if (!Array.isArray(requirements) || !requirements.length) throw new Error('缺少浏览器版本清单');
  for (const item of requirements) {
    try {
      const relative = path.relative(path.resolve(browsersDir), path.resolve(item.directory));
      if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('版本目录越出物料路径');
      if (!fs.statSync(item.marker).isFile()) throw new Error('缺少安装完成标记');
      const minimum = item.name === 'chromium' ? (item.platform === 'darwin' ? 64 * 1024 : 1024 * 1024) : 10 * 1024 * 1024;
      validateBrowserBinary(item.executable, item.platform, minimum);
      if (item.platform === 'win32' && item.name === 'chromium')
        validateBrowserBinary(path.join(path.dirname(item.executable), 'chrome.dll'), item.platform);
    } catch (error) {
      throw new Error(`Chromium 物料与当前 fat jar 不匹配或不完整：${item.name} revision ${item.revision}\n  ${item.executable}\n  ${error.message}\n  ${repair}\n  若该 revision 已损坏但安装标记仍在，请先将此目录移出 browsers 后再备料：${item.directory}\n  不需要清理其他旧版本目录。`);
    }
  }
  return requirements;
}
