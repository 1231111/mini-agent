import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { ensureDesktopModelConfigKey } = require('../mini-agent-desktop/src/model-config-key.cjs');
const KEY = Buffer.alloc(32, 7).toString('base64');

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-model-key-test-'));
  t.after(() => {
    const target = path.resolve(directory);
    assert.equal(path.dirname(target), path.resolve(os.tmpdir()));
    assert.ok(path.basename(target).startsWith('miniagent-model-key-test-'));
    fs.rmSync(target, { recursive: true, force: true });
  });
  const wrappingKey = crypto.randomBytes(32);
  const storage = {
    encryptCalls: 0,
    isEncryptionAvailable: () => true,
    getSelectedStorageBackend: () => 'gnome_libsecret',
    encryptString(value) {
      this.encryptCalls++;
      const nonce = crypto.randomBytes(12);
      const cipher = crypto.createCipheriv('aes-256-gcm', wrappingKey, nonce);
      const bytes = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
      return Buffer.concat([nonce, cipher.getAuthTag(), bytes]);
    },
    decryptString(bytes) {
      const decipher = crypto.createDecipheriv('aes-256-gcm', wrappingKey, bytes.subarray(0, 12));
      decipher.setAuthTag(bytes.subarray(12, 28));
      return Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString('utf8');
    }
  };
  const options = { keyPath: path.join(directory, 'model-config-key.json'), safeStorage: storage, platform: 'win32' };
  return { directory, storage, options, ensure: changes => ensureDesktopModelConfigKey({ ...options, ...changes }) };
}

test('a new install persists only an OS-protected key and reuses the same key after restart', t => {
  const f = fixture(t);
  const first = f.ensure();
  assert.equal(Buffer.from(first, 'base64').length, 32);
  const disk = fs.readFileSync(f.options.keyPath, 'utf8');
  assert.equal(JSON.parse(disk).protection, 'electron-safe-storage');
  assert.ok(!disk.includes(first));
  assert.equal(f.ensure({ randomBytes() { assert.fail('a restart must not generate a new key'); } }), first);
  assert.equal(f.storage.encryptCalls, 1);
  assert.ok(fs.existsSync(f.options.keyPath + '.managed'));
  assert.equal(fs.readdirSync(f.directory).some(name => name.endsWith('.tmp')), false);
  if (process.platform !== 'win32') assert.equal(fs.statSync(f.options.keyPath).mode & 0o777, 0o600);
});

test('an explicit valid environment key takes precedence without requiring an OS keyring', t => {
  const f = fixture(t);
  const automatic = f.ensure();
  const original = fs.readFileSync(f.options.keyPath, 'utf8');
  const external = f.ensure({ configuredKey: KEY, safeStorage: { isEncryptionAvailable: () => false } });
  assert.equal(external, KEY);
  assert.notEqual(external, automatic);
  assert.equal(fs.readFileSync(f.options.keyPath, 'utf8'), original, 'an explicit override must not destroy the original managed key');
  const marker = fs.readFileSync(f.options.keyPath + '.external', 'utf8');
  assert.ok(!marker.includes(KEY));
  assert.equal(JSON.parse(marker).source, 'environment');
});

test('removing an earlier explicit environment key fails instead of silently selecting another key', t => {
  const f = fixture(t);
  f.ensure({ configuredKey: KEY });
  assert.throws(() => f.ensure(), /此前使用显式 MODEL_CONFIG_ENCRYPTION_KEY/);
  assert.equal(fs.existsSync(f.options.keyPath), false);
  assert.equal(f.ensure({ configuredKey: KEY }), KEY);
});

test('malformed explicit keys fail before any file is generated and error messages never echo them', t => {
  const f = fixture(t);
  for (const value of ['wrong-secret-value', Buffer.alloc(31).toString('base64'), '_'.repeat(43), KEY.slice(0, 8) + ' ' + KEY.slice(8)]) {
    assert.throws(() => f.ensure({ configuredKey: value }), error =>
      /32 字节/.test(error.message) && !error.message.includes(value));
  }
  assert.deepEqual(fs.readdirSync(f.directory), []);
  assert.equal(f.ensure({ configuredKey: KEY.replace(/=$/, '') }), KEY, 'unpadded Base64 is normalized for Java');
});

test('corrupt and undecryptable managed keys are preserved and never regenerated', t => {
  const f = fixture(t);
  f.ensure();
  const original = fs.readFileSync(f.options.keyPath);
  assert.throws(() => f.ensure({ safeStorage: { ...f.storage, decryptString() { throw new Error('raw-private-error'); } } }), error =>
    /原文件已保留/.test(error.message) && !error.message.includes('raw-private-error'));
  assert.deepEqual(fs.readFileSync(f.options.keyPath), original);
  fs.writeFileSync(f.options.keyPath, '{broken');
  assert.throws(() => f.ensure({ randomBytes() { assert.fail('corruption must never rotate a key'); } }), /原文件已保留/);
  assert.equal(fs.readFileSync(f.options.keyPath, 'utf8'), '{broken');
});

test('a missing previously installed key fails closed even when OS encryption works', t => {
  const f = fixture(t);
  f.ensure();
  fs.unlinkSync(f.options.keyPath);
  assert.throws(() => f.ensure(), /密钥文件缺失/);
  assert.equal(fs.existsSync(f.options.keyPath), false);
});

test('unavailable OS protection and Linux plaintext backends never produce a fallback key', t => {
  const f = fixture(t);
  for (const changes of [
    { safeStorage: { isEncryptionAvailable: () => false } },
    { platform: 'linux', safeStorage: { ...f.storage, getSelectedStorageBackend: () => 'basic_text' } },
    { platform: 'linux', safeStorage: { ...f.storage, getSelectedStorageBackend: undefined } }
  ]) assert.throws(() => f.ensure(changes), /系统安全存储不可用/);
  assert.deepEqual(fs.readdirSync(f.directory), []);
  assert.equal(f.ensure({ platform: 'linux' }).length, 44);
});

test('safeStorage encryption failure does not publish a half-initialized key', t => {
  const f = fixture(t);
  assert.throws(() => f.ensure({ safeStorage: { ...f.storage, encryptString() { throw new Error('raw-private-error'); } } }), error =>
    /无法创建/.test(error.message) && !error.message.includes('raw-private-error'));
  assert.deepEqual(fs.readdirSync(f.directory), []);
});

test('a concurrent first-start publisher wins atomically without either caller replacing its key', t => {
  const f = fixture(t);
  const winner = fixture(t);
  const sharedStorage = f.storage;
  const winnerKey = ensureDesktopModelConfigKey({ ...winner.options, safeStorage: sharedStorage });
  const winnerRecord = fs.readFileSync(winner.options.keyPath);
  let injected = false;
  const io = { ...fs, linkSync(source, target) {
    if (target === f.options.keyPath && !injected) {
      injected = true;
      fs.writeFileSync(target, winnerRecord, { flag: 'wx', mode: 0o600 });
      const error = new Error('other process published first'); error.code = 'EEXIST'; throw error;
    }
    return fs.linkSync(source, target);
  } };
  assert.equal(f.ensure({ io }), winnerKey);
  assert.deepEqual(fs.readFileSync(f.options.keyPath), winnerRecord);
  assert.equal(fs.readdirSync(f.directory).some(name => name.endsWith('.tmp')), false);
});

test('filesystem publication failures fail closed and clean temporary encrypted records', t => {
  const f = fixture(t);
  const io = { ...fs, linkSync() { const error = new Error('access denied'); error.code = 'EACCES'; throw error; } };
  assert.throws(() => f.ensure({ io }), /无法安全保存/);
  assert.deepEqual(fs.readdirSync(f.directory), []);
});

test('main injects the protected key before spawning Java and never exposes it through preload', () => {
  const main = fs.readFileSync(new URL('../mini-agent-desktop/main.js', import.meta.url), 'utf8');
  const preload = fs.readFileSync(new URL('../mini-agent-desktop/preload.js', import.meta.url), 'utf8');
  const startup = main.slice(main.indexOf('function startBackend('), main.indexOf('function stopBackend('));
  assert.match(startup, /env\.MODEL_CONFIG_ENCRYPTION_KEY = ensureDesktopModelConfigKey\(/);
  assert.ok(startup.indexOf('env.MODEL_CONFIG_ENCRYPTION_KEY') < startup.indexOf('backend = spawn('));
  assert.doesNotMatch(preload, /MODEL_CONFIG_ENCRYPTION_KEY|model-config-key\.json/);
});
