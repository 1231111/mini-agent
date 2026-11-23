/**
 * electron-builder afterPack 钩子：把 Electron 官方发布的「含专有编解码器」ffmpeg
 * 换进打包产物。
 *
 * 为什么要换：
 *   Electron 自带的 ffmpeg 出于专利原因剥掉了 H.264 / AAC。MiniAgent 有生视频、
 *   生歌这类产出 mp4 / m4a 的工具，前端用 <video>/<audio> 播放时这些格式会直接黑屏
 *   或静音。换成 Electron release 里的 ffmpeg-v<ver>-<platform>-<arch>.zip 即可。
 *
 * 为什么不用 extraFiles 配置：
 *   四个平台的落点不一样，而且 macOS 那个要钻进 Framework 内部的多层目录，
 *   配置项表达不了。集中在一个钩子里，各平台路径一目了然。
 *
 *   注意 macOS 的骨架路径是 Electron 自己的布局，不是我们的选择：
 *     MiniAgent.app/Contents/Frameworks/Electron Framework.framework/
 *       Versions/A/Libraries/libffmpeg.dylib
 *
 * 执行时机：
 *   afterPack 在 electron-builder 的签名步骤之前，所以这里替换 dylib 之后再签名
 *   才是正确的顺序。有正式证书时，紧随其后的签名步骤会覆盖本节做的 ad-hoc 签名。
 */

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

/** 各平台 ffmpeg 的文件名 —— 由 Chromium 的构建产物决定，不是可选项。 */
const FFMPEG_FILE = {
  win32: 'ffmpeg.dll',
  linux: 'libffmpeg.so',
  darwin: 'libffmpeg.dylib'
};

/**
 * 出厂内置模型凭据（平台级 MIMO_API_KEY）。
 *
 * 为什么放在 afterPack 而不是 extraResources：
 *   extraResources 要求源文件在仓库里存在，而真实 Key 绝不能进版本库。
 *   afterPack 直接写已经成型的产物目录，仓库里一个带 Key 的文件都不需要。
 *   换一台构建机也不用改脚本 —— 那台机器的 ~/.miniagent/config.yml 里有
 *   MIMO_API_KEY 就自动内置，没有就退化成「不带 Key」的包。
 *
 * 安全边界：resources/runtime-model-key.json 是明文，拿到安装包的人能读出来，
 *   所以只适合平台级共享 Key，绝不用来放用户私有凭据。想出不带 Key 的包：
 *   构建前设置 MINI_AGENT_BUNDLE_MODEL_KEY=off。
 */
const BUNDLE_FILE = 'runtime-model-key.json';

function bundledKeyResourcesDir(platform, appOutDir, productFilename) {
  // 与打包态 Electron 的 process.resourcesPath 保持一致。
  if (platform === 'darwin') {
    return path.join(appOutDir, `${productFilename}.app`, 'Contents', 'Resources');
  }
  return path.join(appOutDir, 'resources');
}

/** 从构建机外部配置取 Key；不引入 YAML 依赖，格式由 config.example.yml 约定。 */
function readBuildMachineModelKey() {
  const explicit = process.env.MINI_AGENT_BUNDLE_MODEL_KEY;
  if (typeof explicit === 'string' && explicit.trim()) {
    const value = explicit.trim();
    if (/^(off|none|false|0)$/i.test(value)) return { key: '', source: '显式关闭' };
    return { key: value, source: 'MINI_AGENT_BUNDLE_MODEL_KEY' };
  }

  const home = process.env.MINI_AGENT_HOME || path.join(os.homedir(), '.miniagent');
  const file = path.join(home, 'config.yml');
  if (!fs.existsSync(file)) return { key: '', source: `构建机没有 ${file}` };

  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (/^\s*#/.test(line)) continue;
    const m = line.match(/^\s*MIMO_API_KEY\s*:\s*(?:'([^']*)'|"([^"]*)"|([^\s#]+))/);
    if (m) {
      const key = (m[1] ?? m[2] ?? m[3] ?? '').trim();
      return { key, source: key ? file : `${file} 里 MIMO_API_KEY 为空` };
    }
  }
  return { key: '', source: `${file} 里没有 MIMO_API_KEY` };
}

function maskKey(key) {
  return key.length <= 10 ? '***' : key.slice(0, 6) + '...' + key.slice(-4);
}

function injectBundledModelKey(context) {
  const dir = bundledKeyResourcesDir(
    context.electronPlatformName,
    context.appOutDir,
    context.packager.appInfo.productFilename
  );
  const file = path.join(dir, BUNDLE_FILE);
  const { key, source } = readBuildMachineModelKey();

  if (!key) {
    // 产物目录可能被复用：清掉上一次留下的 Key，避免「关了开关却还带着旧 Key」。
    if (fs.existsSync(file)) {
      fs.unlinkSync(file);
      console.log(`[after-pack] 已移除上一次的出厂模型凭据（${source}）`);
    } else {
      console.log(`[after-pack] 本包不内置模型凭据：${source}`);
    }
    return;
  }

  if (!fs.existsSync(dir)) {
    console.warn(`[after-pack] 资源目录不存在，跳过模型凭据注入：${dir}`);
    return;
  }

  fs.writeFileSync(file, JSON.stringify({
    version: 1,
    MIMO_API_KEY: key,
    note: '平台级共享凭据，明文。仅用于全新客户机首启生成 ~/.miniagent/config.yml。'
  }, null, 2) + '\n', { encoding: 'utf8' });
  console.log(`[after-pack] 已内置出厂模型凭据 MIMO_API_KEY=${maskKey(key)}（来源：${source}）`);
}

function ffmpegTarget(platform, appOutDir, productFilename) {
  const name = FFMPEG_FILE[platform];
  if (platform === 'darwin') {
    return path.join(
      appOutDir,
      `${productFilename}.app`,
      'Contents',
      'Frameworks',
      'Electron Framework.framework',
      'Versions',
      'A',
      'Libraries',
      name
    );
  }
  // Windows 与 Linux 都在 app 根目录，与 Electron 主可执行文件同级。
  return path.join(appOutDir, name);
}

exports.default = async function afterPack(context) {
  const platform = context.electronPlatformName;
  const appOutDir = context.appOutDir;
  const productFilename = context.packager.appInfo.productFilename;

  // 必须在任何 ffmpeg 早退之前执行：ffmpeg 物料缺失时下面会直接 return，
  // 但「包里带不带模型凭据」与 ffmpeg 无关，不该被它连带跳过。
  injectBundledModelKey(context);

  const name = FFMPEG_FILE[platform];
  if (!name) {
    console.warn(`[after-pack] 未知平台 ${platform}，跳过 ffmpeg 替换`);
    return;
  }

  const src = path.join(__dirname, 'ffmpeg', name);
  const dst = ffmpegTarget(platform, appOutDir, productFilename);

  if (!fs.existsSync(src)) {
    console.warn(
      `[after-pack] 未找到 ${src}，保留 Electron 自带的 ffmpeg。\n` +
      '  自带版本不含 H.264 / AAC —— 应用内播放 mp4 / m4a 会失败。\n' +
      '  修复：跑一次完整构建（node scripts/build-desktop.mjs，不要加 --skip-ffmpeg）。'
    );
    return;
  }

  if (!fs.existsSync(path.dirname(dst))) {
    console.warn(
      `[after-pack] 目标目录不存在，Electron 的目录布局可能已变：\n  ${path.dirname(dst)}\n` +
      '  未做替换。请核对 electron 版本与官方发布包结构。'
    );
    return;
  }

  fs.copyFileSync(src, dst);
  const mbSize = (fs.statSync(dst).size / 1024 / 1024).toFixed(1);
  console.log(`[after-pack] ffmpeg 已替换：${name}（${mbSize} MB）`);

  if (platform === 'darwin') {
    resignMac(appOutDir, productFilename);
  }
};

/**
 * 替换 framework 内的 dylib 会让原有签名失效，而 Apple Silicon 强制要求
 * 每一段可执行代码都有有效签名 —— 签名不对的后果不是"警告"，是直接拒绝启动。
 *
 * 这里做 ad-hoc 签名（--sign -）。正式出厂应换成开发者证书 + 公证，
 * 那种情况下 electron-builder 的签名步骤会在本钩子之后重新签一遍，本节结果被覆盖。
 */
function resignMac(appOutDir, productFilename) {
  const appPath = path.join(appOutDir, `${productFilename}.app`);
  const r = spawnSync('codesign', ['--force', '--deep', '--sign', '-', appPath], { encoding: 'utf8' });

  if (r.status === 0) {
    console.log('[after-pack] 已对 .app 重新做 ad-hoc 签名');
    return;
  }

  console.warn(
    '[after-pack] ad-hoc 签名失败 —— Apple Silicon 上应用可能无法启动。\n' +
    `  ${(r.stderr || r.error || '').toString().trim()}\n` +
    '  若已配置正式证书，可忽略（electron-builder 会在本钩子之后重新签名）。'
  );
}

// 供 node --test 直接验证注入逻辑，不必拉起整个 electron-builder。
exports.BUNDLE_FILE = BUNDLE_FILE;
exports.bundledKeyResourcesDir = bundledKeyResourcesDir;
exports.readBuildMachineModelKey = readBuildMachineModelKey;
exports.injectBundledModelKey = injectBundledModelKey;
