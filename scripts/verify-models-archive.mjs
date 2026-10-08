#!/usr/bin/env node
/**
 * 校验物料归档的 sha256（CI 与本机共用）。
 *
 * 为什么要一个专门的脚本，而不是在 workflow 里调 `sha256sum -c`：
 *   **macOS runner 上根本没有 sha256sum** —— 它只有 `shasum -a 256`。
 *   写 `if command -v sha256sum; then ... else ...; fi` 这种分支，
 *   等于把"四个平台口径一致"这件事押在两条不同实现的一致性上，
 *   而这个校验本身就是为了证明"四平台拿到的是同一份字节"。
 *   用 Node 的 crypto 算，四个 runner 上跑的是同一份代码。
 *
 * 另一个理由：下载失败的形态是**静默**的。GitHub 对不存在的 asset 会返回
 * 一个 HTML 错误页，curl -f 能拦住非 2xx，但如果中间有代理改写响应体，
 * 拿到一个 1KB 的 HTML 文件是完全可能的 —— 它会被当成"模型文件"放行到
 * 建会话那一步才失败。所以下载完立刻按 sha256 卡一道。
 *
 * 用法：
 *   node scripts/verify-models-archive.mjs <archive> <archive.sha256>
 *   node scripts/verify-models-archive.mjs <archive> <archive.sha256> --list
 *
 * 退出码：0 通过；1 不符或参数错。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const log = (msg) => console.log(msg);
const fail = (msg) => {
  console.error(`\n校验失败：${msg}\n`);
  process.exit(1);
};

/**
 * 流式算 sha256。归档 205MB，不要整个读进内存。
 *
 * ⚠ 必须等 `'close'` 而不是 `'end'` 才 resolve（实测踩过）：
 *   `'end'` 只表示"数据读完了"，底层文件句柄要到 `'close'` 才真正释放。
 *   在 Windows 上，句柄还开着时让另一个进程打开同一个文件会拿到 **EBUSY** ——
 *   本脚本紧接着 spawn 一个 tar 去列成员，就是这个组合，报错是
 *   `spawnSync tar EBUSY`，而同一时刻在命令行手敲 `tar -tzf` 却完全正常，
 *   排查时很容易误判成 tar 或权限的问题。
 */
function sha256File(p) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(p)
      .on('error', reject)
      .on('data', (d) => h.update(d))
      .on('close', () => resolve(h.digest('hex')));
  });
}

/**
 * 从 sha256sum 文件里取摘要。
 *
 * 格式有两行都想容错：标准是 `<hex>  <name>`（两个空格，文本模式有前导 `*`），
 * 但有些工具会写单空格。只取第一个空白分隔的 token 即可 —— 它一定是摘要。
 */
function readExpectedDigest(shaFile) {
  const raw = fs.readFileSync(shaFile, 'utf8');
  const first = raw.split(/\r?\n/).find((l) => l.trim().length > 0);
  if (!first) fail(`${shaFile} 是空文件`);
  const hex = first.trim().split(/\s+/)[0].toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(hex)) {
    fail(
      `${shaFile} 里不是合法的 sha256：${hex}\n` +
      '  期望 64 位十六进制。这个文件由 scripts/pack-models.mjs 生成。'
    );
  }
  return hex;
}

async function main() {
  const argv = process.argv.slice(2).filter((a) => a !== '--list');
  const list = process.argv.includes('--list');

  if (argv.length !== 2) {
    fail(
      '用法：node scripts/verify-models-archive.mjs <archive> <archive.sha256> [--list]\n' +
      `  收到 ${argv.length} 个位置参数：${argv.join(' ') || '(无)'}`
    );
  }
  const [archive, shaFile] = argv.map((p) => path.resolve(p));

  if (!fs.existsSync(archive)) fail(`归档不存在：${archive}`);
  if (!fs.existsSync(shaFile)) fail(`sha256 文件不存在：${shaFile}`);

  const bytes = fs.statSync(archive).size;
  const expected = readExpectedDigest(shaFile);
  const actual = await sha256File(archive);

  if (actual !== expected) {
    fail(
      `${path.basename(archive)} 的 sha256 不符\n` +
      `  期望 ${expected}\n` +
      `  实际 ${actual}\n` +
      `  归档 ${bytes.toLocaleString('en-US')} B\n` +
      '  常见原因：\n' +
      '    1) 下载被中断或经代理改写过（归档里会混进 HTML 错误页）\n' +
      '    2) release asset 被重新上传过 —— 同名不同内容。换物料请新开 tag\n' +
      '       （models-v2），不要覆盖已有 release 的 asset，否则已发布安装包\n' +
      '       用的到底是哪份物料将无法追溯。\n' +
      '  重新下载后重试；若仍不符，请核对 workflow 里的 MODELS_RELEASE_TAG。'
    );
  }

  log(`sha256 通过  ${actual}`);
  log(`归档  ${path.basename(archive)}  ${(bytes / 1024 / 1024).toFixed(1)} MB`);

  if (list) {
    log('');
    log('成员：');
    // 两条 Windows 上的坑都在这儿，缺一条就报一个看不懂的错：
    //
    // ① `-f` 的值里出现带盘符的绝对路径 → GNU tar 按 host:path 远程归档语法
    //    解析 `D:`，把 "D" 当主机名，报 "Cannot connect to D: resolve failed"。
    //    所以传相对文件名 + cwd 定位。
    //
    // ② **stdin 必须显式写 'ignore'**。spawnSync 的 stdio 默认是
    //    ['pipe','pipe','pipe']，而 msys2 版 GNU tar 在 stdin 是匿名管道时
    //    启动就失败：status=null、error.code='EBUSY'、stderr 全空。
    //    实测矩阵（Node 22 / Windows / Git Bash 的 tar 1.35）：
    //      不传 stdio         → EBUSY
    //      ['pipe','pipe','pipe'] → EBUSY
    //      ['ignore','pipe','pipe'] → 正常
    //      命令行手敲同一条 tar → 正常（这就是它难查的地方）
    //    pack-models.mjs 因为顺手写了 'ignore' 才没踩到；这里原先漏了。
    const r = spawnSync('tar', ['-tzf', path.basename(archive)], {
      cwd: path.dirname(archive),
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe']
    });
    if (r.error || r.status !== 0) {
      // 列成员只是附加信息，不改变校验结论 —— 不因为 tar 缺了就报失败。
      log(`      (无法列出：${r.error ? r.error.message : `tar 退出码 ${r.status}`})`);
    } else {
      for (const line of r.stdout.split(/\r?\n/).filter(Boolean)) log(`      ${line}`);
    }
  }
}

main().catch((e) => {
  console.error(`\n校验异常终止：${e && e.stack ? e.stack : e}\n`);
  process.exit(1);
});
