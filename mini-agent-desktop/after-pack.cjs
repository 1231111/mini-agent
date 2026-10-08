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
const path = require('node:path');

/** 各平台 ffmpeg 的文件名 —— 由 Chromium 的构建产物决定，不是可选项。 */
const FFMPEG_FILE = {
  win32: 'ffmpeg.dll',
  linux: 'libffmpeg.so',
  darwin: 'libffmpeg.dylib'
};

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
