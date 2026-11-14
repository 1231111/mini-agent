const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const CONTEXT = 'miniagent:model-config-key:v1:';
const MAX_RECORD_BYTES = 16384;

function validatedKey(value) {
  const key = typeof value === 'string' ? value.trim() : '';
  if (!/^[A-Za-z0-9+/]{43}=?$/.test(key)) throw new Error(
    'MODEL_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥，请恢复原值或修正配置。');
  const decoded = Buffer.from(key, 'base64');
  if (decoded.length !== 32 || decoded.toString('base64').replace(/=+$/, '') !== key.replace(/=+$/, ''))
    throw new Error('MODEL_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥，请恢复原值或修正配置。');
  return decoded.toString('base64');
}

function requireSecureStorage(safeStorage, platform) {
  let available = false;
  try {
    available = safeStorage && safeStorage.isEncryptionAvailable();
    if (available && platform === 'linux') {
      // Electron basic_text uses a known plaintext password, not the OS keyring.
      const backend = typeof safeStorage.getSelectedStorageBackend === 'function'
        ? safeStorage.getSelectedStorageBackend() : '';
      available = ['gnome_libsecret', 'kwallet', 'kwallet5', 'kwallet6'].includes(backend);
    }
  } catch (_) { available = false; }
  if (!available) throw new Error(
    '系统安全存储不可用，无法保护模型凭据。请解锁系统钥匙串/密钥环后重试，或显式配置稳定的 MODEL_CONFIG_ENCRYPTION_KEY；不会使用明文保护。');
}

function readRecord(file, io) {
  const stat = io.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_RECORD_BYTES)
    throw new Error('Invalid key record');
  return JSON.parse(io.readFileSync(file, 'utf8'));
}

function readProtectedKey(file, safeStorage, io) {
  try {
    const record = readRecord(file, io);
    if (record.version !== 1 || record.protection !== 'electron-safe-storage'
        || typeof record.encryptedKey !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(record.encryptedKey))
      throw new Error('Invalid key record');
    const value = safeStorage.decryptString(Buffer.from(record.encryptedKey, 'base64'));
    if (typeof value !== 'string' || !value.startsWith(CONTEXT)) throw new Error('Invalid key context');
    return validatedKey(value.slice(CONTEXT.length));
  } catch (_) {
    throw new Error('桌面模型凭据保护密钥损坏或无法由当前系统用户解密。请恢复原密钥文件及系统用户环境，或提供原 MODEL_CONFIG_ENCRYPTION_KEY；原文件已保留，不会自动重建。');
  }
}

/** A complete fsynced record is published without ever replacing another key. */
function writeRecord(file, record, io, replace = false) {
  io.mkdirSync(path.dirname(file), { recursive: true });
  const temporary = file + '.' + crypto.randomUUID() + '.tmp';
  let fd;
  try {
    fd = io.openSync(temporary, 'wx', 0o600);
    io.writeFileSync(fd, JSON.stringify(record) + '\n', 'utf8');
    io.fsyncSync(fd);
    io.closeSync(fd);
    fd = undefined;
    if (replace) io.renameSync(temporary, file);
    else io.linkSync(temporary, file);
  } finally {
    if (fd !== undefined) io.closeSync(fd);
    try { io.unlinkSync(temporary); } catch (_) { /* No secret is stored as plaintext in the temporary file. */ }
  }
}

/**
 * Called after app.ready, before Java is spawned. The raw AES key is only returned
 * in memory for the backend environment; the renderer never receives it.
 */
function ensureDesktopModelConfigKey({ keyPath, configuredKey, safeStorage,
  platform = process.platform, io = fs, randomBytes = crypto.randomBytes }) {
  const externalMarker = keyPath + '.external';
  const managedMarker = keyPath + '.managed';
  if (typeof configuredKey === 'string' && configuredKey.trim()) {
    const key = validatedKey(configuredKey);
    const fingerprint = crypto.createHash('sha256').update(key).digest('hex');
    try {
      let previous;
      try { previous = readRecord(externalMarker, io); } catch (_) { /* Replace metadata, never the protected key. */ }
      if (previous?.version !== 1 || previous?.fingerprint !== fingerprint)
        writeRecord(externalMarker, { version: 1, source: 'environment', fingerprint }, io, true);
    } catch (_) {
      throw new Error('无法记录模型凭据保护配置。请确认客户端数据目录可写后重试；不会自动切换加密密钥。');
    }
    return key;
  }
  if (io.existsSync(externalMarker)) throw new Error(
    '此客户端此前使用显式 MODEL_CONFIG_ENCRYPTION_KEY。请恢复原环境变量后启动，避免切换密钥导致已有模型凭据无法解密。');

  requireSecureStorage(safeStorage, platform);
  const confirmManagedKey = key => {
    const fingerprint = crypto.createHash('sha256').update(key).digest('hex');
    try {
      if (!io.existsSync(managedMarker)) {
        try { writeRecord(managedMarker, { version: 1, source: 'managed', fingerprint }, io); }
        catch (error) { if (error.code !== 'EEXIST') throw error; }
      }
      const marker = readRecord(managedMarker, io);
      if (marker.version !== 1 || marker.source !== 'managed' || marker.fingerprint !== fingerprint)
        throw new Error('Managed key metadata mismatch');
    } catch (_) {
      throw new Error('模型凭据保护密钥记录不一致或无法保存。请恢复原密钥文件及配套记录；不会自动更换密钥。');
    }
    return key;
  };
  if (io.existsSync(keyPath)) return confirmManagedKey(readProtectedKey(keyPath, safeStorage, io));
  if (io.existsSync(managedMarker)) throw new Error(
    '桌面模型凭据保护密钥文件缺失。请恢复原 model-config-key.json 文件；不会生成新密钥覆盖已有凭据。');
  let key;
  let record;
  try {
    key = validatedKey(randomBytes(32).toString('base64'));
    const encrypted = safeStorage.encryptString(CONTEXT + key);
    if (!Buffer.isBuffer(encrypted) || !encrypted.length) throw new Error('Empty protected key');
    record = { version: 1, protection: 'electron-safe-storage', encryptedKey: encrypted.toString('base64') };
  } catch (_) {
    throw new Error('系统安全存储无法创建模型凭据保护密钥，请解锁系统钥匙串/密钥环后重试，或显式配置 MODEL_CONFIG_ENCRYPTION_KEY。');
  }
  try {
    writeRecord(keyPath, record, io);
  } catch (error) {
    if (error.code === 'EEXIST') return confirmManagedKey(readProtectedKey(keyPath, safeStorage, io));
    throw new Error('无法安全保存模型凭据保护密钥，请确认客户端数据目录支持原子文件创建且可写；不会使用临时或明文密钥。');
  }
  // Verify disk and OS protection before handing any key to the backend.
  const persisted = readProtectedKey(keyPath, safeStorage, io);
  if (persisted !== key) throw new Error('模型凭据保护密钥校验失败，请恢复原密钥文件后重试。');
  return confirmManagedKey(persisted);
}

module.exports = { ensureDesktopModelConfigKey };
