/**
 * 出厂内置模型凭据 → 客户机首启下发。
 *
 * 为什么需要这一层：
 *   Spring 的 `spring.config.import` 只认 ${MINI_AGENT_HOME}/config.yml，
 *   而安装包里不带这个文件（config.example.yml 刻意不随包分发）。全新电脑
 *   装上客户端后，多模态 vision 轮次 / 全局模型回落 / 生图 mimo 后端都会因为
 *   平台级 MIMO_API_KEY 为空而 401 或「未配置 MIMO_API_KEY」。
 *
 * 为什么写文件，而不是给后端进程设 MIMO_API_KEY 环境变量：
 *   OS 环境变量在 Spring 中的优先级高于 spring.config.import 的 config data。
 *   壳若无条件注入，用户日后自己在 config.yml 里换的 Key 会被环境变量静默
 *   覆盖，变成「改了配置不生效」。写文件且「已存在绝不覆盖」才能保证
 *   「用户配置优先，出厂 Key 只是全新机器的兜底」。
 *
 * 边界：
 *   - 只在 app.isPackaged 时执行；开发态沿用构建机自己的 config.yml。
 *   - 目标 config.yml 已存在时什么都不做，包括不追加、不改写。
 *   - 包内 runtime-model-key.json 是明文，不是加密容器。它只是把
 *     「每台客户机手工配一遍」变成「构建机配一次」；拿到安装包的人能读出它，
 *     因此只适合放平台级共享 Key，绝不用来放用户私有凭据。
 *     不想内置时，构建前设置 MINI_AGENT_BUNDLE_MODEL_KEY=off。
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const BUNDLE_FILE = 'runtime-model-key.json';
const CONFIG_FILE = 'config.yml';

/** 读出厂凭据；文件缺失、格式不对、值为空都视为「没有」，绝不抛给启动流程。 */
function readBundledKey(resourcesPath, io = fs) {
  try {
    const record = JSON.parse(io.readFileSync(path.join(resourcesPath, BUNDLE_FILE), 'utf8'));
    if (!record || record.version !== 1 || typeof record.MIMO_API_KEY !== 'string') return '';
    return record.MIMO_API_KEY.trim();
  } catch (_) {
    return '';
  }
}

/** YAML 单引号标量：内部单引号按规范写成两个。 */
function yamlSingleQuoted(value) {
  return "'" + value.replace(/'/g, "''") + "'";
}

function configBody(key) {
  return [
    '# MiniAgent 出厂模型凭据（客户端首次启动时自动生成）',
    '# 由 application.yml 的 spring.config.import 加载；不在版本库、不由升级包覆盖。',
    '# 想换成自己的 Key：直接改本文件，然后重启客户端。',
    '#',
    '# 注意：不要加 LLM_API_KEY: \'\'。application.yml 用的是',
    '# ${LLM_API_KEY:${MIMO_API_KEY:}}，空字符串算「属性已存在」，会盖掉这里的 Key。',
    '',
    'MIMO_API_KEY: ' + yamlSingleQuoted(key),
    ''
  ].join('\n');
}

/**
 * @returns {{status: string, target?: string}} status:
 *   skipped-dev / skipped-no-bundle / skipped-existing / written
 */
function ensureBundledModelConfig({
  dataDir,
  resourcesPath,
  packaged = true,
  io = fs,
  randomUUID = () => crypto.randomUUID()
}) {
  if (!packaged) return { status: 'skipped-dev' };

  const key = readBundledKey(resourcesPath, io);
  if (!key) return { status: 'skipped-no-bundle' };

  const target = path.join(dataDir, CONFIG_FILE);
  if (io.existsSync(target)) return { status: 'skipped-existing', target };

  // 原子创建：linkSync 在目标已存在时抛 EEXIST，比「先判存在再 rename」更严，
  // 不会因为两个进程竞态覆盖掉用户刚写好的配置。与 model-config-key.cjs 同一套写法。
  io.mkdirSync(dataDir, { recursive: true });
  const temporary = target + '.' + randomUUID() + '.tmp';
  try {
    io.writeFileSync(temporary, configBody(key), { encoding: 'utf8', mode: 0o600 });
    io.linkSync(temporary, target);
    return { status: 'written', target };
  } catch (error) {
    if (error && error.code === 'EEXIST') return { status: 'skipped-existing', target };
    throw new Error('无法写入出厂模型凭据配置：' + (error && error.message ? error.message : error));
  } finally {
    try { io.unlinkSync(temporary); } catch (_) { /* 临时文件可能已被 link 清理 */ }
  }
}

module.exports = {
  BUNDLE_FILE,
  CONFIG_FILE,
  readBundledKey,
  configBody,
  ensureBundledModelConfig
};
