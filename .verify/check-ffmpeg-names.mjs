/**
 * 验证 Electron release 里四个目标平台的 ffmpeg 包内文件名。
 *
 * scripts/build-desktop.mjs 的 FFMPEG_FILE 映射（win32→ffmpeg.dll、darwin→libffmpeg.dylib、
 * linux→libffmpeg.so）是从 install.js 的命名规则推断出来的，没实证过。
 * 这个脚本把四个平台的包都下下来列出条目，把推断变成事实 ——
 * 猜错的后果是 afterPack 静默跳过替换，出厂包里播不了 mp4，且没有任何报错。
 *
 * 用法：node .verify/check-ffmpeg-names.mjs
 */

import zlib from 'node:zlib';

const VERSION = '28.3.3';
const TARGETS = [
  ['win32', 'x64'],
  ['linux', 'x64'],
  ['linux', 'arm64'],
  ['darwin', 'arm64']
];

/** 只列条目名，不需要数据段，所以不读本地头。 */
function unzipList(zip) {
  let eocd = -1;
  const minPos = Math.max(0, zip.length - 22 - 65535);
  for (let i = zip.length - 22; i >= minPos; i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是有效 zip');

  const count = zip.readUInt16LE(eocd + 10);
  const cdSize = zip.readUInt32LE(eocd + 12);
  const cdOffset = zip.readUInt32LE(eocd + 16);
  const cd = zip.subarray(cdOffset, cdOffset + cdSize);

  const names = [];
  let p = 0;
  for (let i = 0; i < count; i++) {
    if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) break;
    const nameLen = cd.readUInt16LE(p + 28);
    const extraLen = cd.readUInt16LE(p + 30);
    const commentLen = cd.readUInt16LE(p + 32);
    names.push(cd.toString('utf8', p + 46, p + 46 + nameLen));
    p += 46 + nameLen + extraLen + commentLen;
  }
  return names;
}

let allOk = true;
const observed = {};

for (const [p, a] of TARGETS) {
  const asset = `ffmpeg-v${VERSION}-${p}-${a}.zip`;
  const url = `https://github.com/electron/electron/releases/download/v${VERSION}/${asset}`;
  try {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    const names = unzipList(buf).filter((n) => !n.endsWith('/'));
    observed[`${p}-${a}`] = names;
    console.log(`${(p + '-' + a).padEnd(16)} ${buf.length.toString().padStart(9)} B  条目: ${names.join(', ')}`);
  } catch (e) {
    allOk = false;
    console.log(`${(p + '-' + a).padEnd(16)} 下载/解析失败: ${e.message}`);
  }
}

console.log('\n=== 与 build-desktop.mjs 的 FFMPEG_FILE 映射对照 ===');
const EXPECT = { win32: 'ffmpeg.dll', darwin: 'libffmpeg.dylib', linux: 'libffmpeg.so' };
for (const [p, a] of TARGETS) {
  const names = observed[`${p}-${a}`] || [];
  const expect = EXPECT[p];
  const hit = names.some((n) => n === expect || n.endsWith('/' + expect));
  console.log(`${(p + '-' + a).padEnd(16)} 期望 ${expect.padEnd(18)} ${hit ? 'OK' : '不匹配'}`);
  if (!hit) allOk = false;
}

console.log(allOk ? '\n全部匹配' : '\n存在不匹配 —— 需要修正 build-desktop.mjs 的 FFMPEG_FILE');
process.exit(allOk ? 0 : 1);
