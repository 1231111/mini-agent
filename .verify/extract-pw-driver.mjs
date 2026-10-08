/**
 * 从 fat jar 里解出 Playwright driver-bundle，然后：
 *   1) 打印包内顶层路径分布 + 最大的若干条目（先看清布局）
 *   2) 解出「非 driver 二进制」的部分（即 playwright 的 JS 运行时）
 *
 * 用法：node .verify/extract-pw-driver.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pipeline } from 'node:stream/promises';

const JAR = 'mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar';
const OUT = '.verify/pw-driver';
const BUNDLE_RE = /^BOOT-INF\/lib\/driver-bundle-\d[^/]*\.jar$/;

function listEntries(filePath) {
  const fd = fs.openSync(filePath, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const tailLen = Math.min(size, 22 + 65535);
    const tail = Buffer.alloc(tailLen);
    fs.readSync(fd, tail, 0, tailLen, size - tailLen);
    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('EOCD not found: ' + filePath);
    const total = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOff = tail.readUInt32LE(eocd + 16);
    const cd = Buffer.alloc(cdSize);
    fs.readSync(fd, cd, 0, cdSize, cdOff);
    const out = [];
    let p = 0;
    for (let i = 0; i < total; i++) {
      if (cd.readUInt32LE(p) !== 0x02014b50) break;
      const method = cd.readUInt16LE(p + 10);
      const compSize = cd.readUInt32LE(p + 20);
      const uncompSize = cd.readUInt32LE(p + 24);
      const nameLen = cd.readUInt16LE(p + 28);
      const extraLen = cd.readUInt16LE(p + 30);
      const commentLen = cd.readUInt16LE(p + 32);
      const localOff = cd.readUInt32LE(p + 42);
      const name = cd.toString('utf8', p + 46, p + 46 + nameLen);
      out.push({ name, method, compSize, uncompSize, localOff });
      p += 46 + nameLen + extraLen + commentLen;
    }
    return out;
  } finally { fs.closeSync(fd); }
}

/** 只读 30 字节本地头算出数据起点，读完立刻关掉自己的 fd（不交给流托管） */
function dataStart(zipPath, entry) {
  const fd = fs.openSync(zipPath, 'r');
  try {
    const h = Buffer.alloc(30);
    fs.readSync(fd, h, 0, 30, entry.localOff);
    if (h.readUInt32LE(0) !== 0x04034b50) throw new Error('bad local header: ' + entry.name);
    return entry.localOff + 30 + h.readUInt16LE(26) + h.readUInt16LE(28);
  } finally { fs.closeSync(fd); }
}

async function extract(zipPath, entry, outPath) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  if (entry.compSize === 0) { fs.writeFileSync(outPath, Buffer.alloc(0)); return; }
  const start = dataStart(zipPath, entry);
  const end = start + entry.compSize - 1;
  const rs = fs.createReadStream(zipPath, { start, end });
  const ws = fs.createWriteStream(outPath);
  if (entry.method === 0) await pipeline(rs, ws);
  else await pipeline(rs, zlib.createInflateRaw(), ws);
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const bundle = listEntries(JAR).find((e) => BUNDLE_RE.test(e.name));
if (!bundle) throw new Error('driver-bundle not found');
console.log('driver-bundle:', bundle.name, (bundle.compSize / 1048576).toFixed(1), 'MB');

const tmpBundle = path.join(OUT, '_bundle.jar');
await extract(JAR, bundle, tmpBundle);

const entries = listEntries(tmpBundle);
console.log('bundle 条目数:', entries.length);

// 顶层路径分布
const top = new Map();
for (const e of entries) {
  const seg = e.name.split('/');
  const key = seg.length > 1 ? seg[0] + '/' + seg[1] : seg[0];
  const cur = top.get(key) || { n: 0, bytes: 0 };
  cur.n++;
  cur.bytes += e.compSize;
  top.set(key, cur);
}
console.log('\n=== 顶层路径分布（压缩后）===');
[...top.entries()].sort((a, b) => b[1].bytes - a[1].bytes).forEach(([k, v]) => {
  console.log(`  ${(v.bytes / 1048576).toFixed(2).padStart(9)} MB  ${String(v.n).padStart(5)} 项  ${k}`);
});

console.log('\n=== 最大的 20 个条目 ===');
[...entries].sort((a, b) => b.compSize - a.compSize).slice(0, 20).forEach((e) => {
  console.log(`  ${(e.compSize / 1048576).toFixed(2).padStart(9)} MB  ${e.name}`);
});

// 解出 win32_x64 的 node.exe + 该平台的 playwright npm 包（JS 运行时）
const want = entries.filter(
  (e) => /^driver\/win32_x64\/(node\.exe|package\/)/.test(e.name) && !e.name.endsWith('/')
);
console.log('\n要解出:', want.length, '项，合计',
  (want.reduce((s, e) => s + e.compSize, 0) / 1048576).toFixed(1), 'MB');
let n = 0;
for (const e of want) {
  await extract(tmpBundle, e, path.join(OUT, 'pw', e.name.replace(/^driver\/win32_x64\//, '')));
  n++;
}
console.log('已解出', n, '个文件 →', OUT + '/pw');

fs.rmSync(tmpBundle, { force: true });
