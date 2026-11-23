const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  BUNDLE_FILE: CLIENT_BUNDLE_FILE,
  CONFIG_FILE,
  configBody,
  ensureBundledModelConfig
} = require('../src/bundled-model-key.cjs');
const {
  BUNDLE_FILE: PACK_BUNDLE_FILE,
  bundledKeyResourcesDir,
  readBuildMachineModelKey,
  injectBundledModelKey
} = require('../after-pack.cjs');

const KEY = 'tp-unit-test-key-0123456789';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-key-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const resources = path.join(root, 'resources');
  const dataDir = path.join(root, 'home');
  fs.mkdirSync(resources, { recursive: true });
  fs.mkdirSync(dataDir, { recursive: true });
  return {
    root, resources, dataDir,
    config: path.join(dataDir, CONFIG_FILE),
    bundle: path.join(resources, CLIENT_BUNDLE_FILE)
  };
}

function writeBundle(f, key, version = 1) {
  fs.writeFileSync(f.bundle, JSON.stringify({ version, MIMO_API_KEY: key }), 'utf8');
}

function context(appOutDir, platform = 'win32') {
  return {
    electronPlatformName: platform,
    appOutDir,
    packager: { appInfo: { productFilename: 'MiniAgent' } }
  };
}

/* ───────── 客户端首启下发 ───────── */

test('全新机器：生成 config.yml，Spring 能解析，且不含会覆盖 Key 的 LLM_API_KEY', (t) => {
  const f = fixture(t);
  writeBundle(f, KEY);

  const result = ensureBundledModelConfig({
    dataDir: f.dataDir, resourcesPath: f.resources, packaged: true
  });
  assert.equal(result.status, 'written');
  assert.equal(result.target, f.config);

  const body = fs.readFileSync(f.config, 'utf8');
  const m = body.match(/^MIMO_API_KEY:\s*'([^']+)'\s*$/m);
  assert.ok(m, '必须是一行合法的 YAML 标量');
  assert.equal(m[1], KEY);
  assert.ok(!/^LLM_API_KEY:/m.test(body), '空 LLM_API_KEY 会盖掉回落值，绝不能写');
});

test('用户已配置 config.yml：绝不覆盖，包括不追加', (t) => {
  const f = fixture(t);
  writeBundle(f, KEY);
  const own = "# my own config\nMIMO_API_KEY: 'user-own-key'\n";
  fs.writeFileSync(f.config, own, 'utf8');

  const result = ensureBundledModelConfig({
    dataDir: f.dataDir, resourcesPath: f.resources, packaged: true
  });
  assert.equal(result.status, 'skipped-existing');
  assert.equal(fs.readFileSync(f.config, 'utf8'), own);
});

test('重复启动幂等：第二次不再写', (t) => {
  const f = fixture(t);
  writeBundle(f, KEY);
  const call = () => ensureBundledModelConfig({
    dataDir: f.dataDir, resourcesPath: f.resources, packaged: true
  });
  assert.equal(call().status, 'written');
  assert.equal(call().status, 'skipped-existing');
});

test('包里没有凭据 / 凭据为空 / 版本不对：都不生成文件', (t) => {
  const cases = [
    () => {},
    (f) => writeBundle(f, ''),
    (f) => writeBundle(f, KEY, 99),
    (f) => fs.writeFileSync(f.bundle, '{ not json', 'utf8')
  ];
  for (const setup of cases) {
    const f = fixture(t);
    setup(f);
    const result = ensureBundledModelConfig({
      dataDir: f.dataDir, resourcesPath: f.resources, packaged: true
    });
    assert.equal(result.status, 'skipped-no-bundle');
    assert.equal(fs.existsSync(f.config), false);
  }
});

test('开发态不干预构建机自己的 config.yml', (t) => {
  const f = fixture(t);
  writeBundle(f, KEY);
  const result = ensureBundledModelConfig({
    dataDir: f.dataDir, resourcesPath: f.resources, packaged: false
  });
  assert.equal(result.status, 'skipped-dev');
  assert.equal(fs.existsSync(f.config), false);
});

test('Key 里含单引号时按 YAML 规范转义', () => {
  const body = configBody("tp-'quoted'-key");
  assert.ok(body.includes("MIMO_API_KEY: 'tp-''quoted''-key'"));
});

/* ───────── 构建期注入 ───────── */

test('afterPack 从构建机 config.yml 读 Key 并写进产物 resources', (t) => {
  const f = fixture(t);
  const appOutDir = path.join(f.root, 'win-unpacked');
  fs.mkdirSync(path.join(appOutDir, 'resources'), { recursive: true });
  const home = path.join(f.root, 'build-home');
  fs.mkdirSync(home, { recursive: true });
  fs.writeFileSync(path.join(home, 'config.yml'),
    "# build machine\nMIMO_API_KEY: '" + KEY + "'\n", 'utf8');

  const savedHome = process.env.MINI_AGENT_HOME;
  const savedExplicit = process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
  process.env.MINI_AGENT_HOME = home;
  delete process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
  t.after(() => {
    if (savedHome === undefined) delete process.env.MINI_AGENT_HOME;
    else process.env.MINI_AGENT_HOME = savedHome;
    if (savedExplicit === undefined) delete process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
    else process.env.MINI_AGENT_BUNDLE_MODEL_KEY = savedExplicit;
  });

  assert.equal(readBuildMachineModelKey().key, KEY);
  injectBundledModelKey(context(appOutDir));

  const record = JSON.parse(fs.readFileSync(
    path.join(appOutDir, 'resources', PACK_BUNDLE_FILE), 'utf8'));
  assert.equal(record.version, 1);
  assert.equal(record.MIMO_API_KEY, KEY);
});

test('MINI_AGENT_BUNDLE_MODEL_KEY=off 时不注入，并清除上一次残留', (t) => {
  const f = fixture(t);
  const appOutDir = path.join(f.root, 'win-unpacked');
  const resources = path.join(appOutDir, 'resources');
  fs.mkdirSync(resources, { recursive: true });
  const stale = path.join(resources, PACK_BUNDLE_FILE);
  fs.writeFileSync(stale, JSON.stringify({ version: 1, MIMO_API_KEY: KEY }), 'utf8');

  const saved = process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
  process.env.MINI_AGENT_BUNDLE_MODEL_KEY = 'off';
  t.after(() => {
    if (saved === undefined) delete process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
    else process.env.MINI_AGENT_BUNDLE_MODEL_KEY = saved;
  });

  injectBundledModelKey(context(appOutDir));
  assert.equal(fs.existsSync(stale), false, '旧 Key 不能被带进新包');
});

test('三平台 resources 路径与打包态 process.resourcesPath 对齐', () => {
  assert.equal(bundledKeyResourcesDir('win32', 'out', 'MiniAgent'),
    path.join('out', 'resources'));
  assert.equal(bundledKeyResourcesDir('linux', 'out', 'MiniAgent'),
    path.join('out', 'resources'));
  assert.equal(bundledKeyResourcesDir('darwin', 'out', 'MiniAgent'),
    path.join('out', 'MiniAgent.app', 'Contents', 'Resources'));
});
