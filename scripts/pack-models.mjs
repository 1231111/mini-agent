#!/usr/bin/env node
/**
 * 把内联 embedding 物料打成一份可发布的归档（四个平台共用同一份）。
 *
 * 为什么需要这个脚本 —— 出厂包要在四个平台上构建，而模型只有一份：
 *   ONNX 变体是**平台无关**的确定性产物，跟 jlink 裁出的 JRE、electron-builder
 *   出的 dmg/AppImage 完全是两类东西。后者不能交叉产出，前者可以且应该只产一次。
 *   在 CI 里各平台重跑一遍导出，等于装四遍 torch、下四遍 1.3GB 原始模型、
 *   跑四遍同样的图优化 —— 换不来任何差异，只换来四个可能不同的结果。
 *
 * 所以出厂路径是：本机导出一次 → 这里打成归档 → 传到 release → 四个 runner 下载。
 * 归档里同时含模型与 tokenizer.json，两者天然成对，不可能版本错配。
 *
 * 用法：
 *   node scripts/pack-models.mjs                          # int8，默认输出路径
 *   node scripts/pack-models.mjs --variant=fp32
 *   node scripts/pack-models.mjs --out=/tmp/models.tar.gz
 *   node scripts/pack-models.mjs --src=.verify/onnx
 *
 * 产出：
 *   <out>          扁平 tar.gz（里面只有文件名，没有目录层级）
 *   <out>.sha256   sha256sum 格式，可直接 `sha256sum -c` 校验
 *
 * 校验与构建期**共用** scripts/lib/model-manifest.mjs 里的同一套规则：
 * 这里放行的物料，build-desktop.mjs 一定也放行；改字节数只有一处。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  MODEL_VARIANTS,
  VARIANT_NAMES,
  ModelSourceError,
  defaultModelSrc,
  defaultTokenizerSrc,
  mb,
  planBytes,
  resolveModelSources
} from './lib/model-manifest.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');

const HELP = `
把内联 embedding 物料打成可发布归档（四平台共用一份）

用法：
  node scripts/pack-models.mjs [选项]

选项：
  --variant=int8       要打包的变体：${VARIANT_NAMES.join(' / ')}（默认 int8）
  --src=<dir>          ONNX 变体所在目录（默认 .verify/onnx）
  --out=<file>         输出归档路径
                       （默认 dist-models/miniagent-models-<variant>.tar.gz）
  --keep-stage         打包后保留暂存目录，便于排查（默认删除）
  -h, --help           显示本帮助

发布给 CI 用（一次性，之后四个平台都从这儿取）：
  node scripts/pack-models.mjs --variant=int8
  gh release create models-v1 \\
     dist-models/miniagent-models-int8.tar.gz \\
     dist-models/miniagent-models-int8.tar.gz.sha256 \\
     --title "models-v1（内联 embedding 物料）" --notes "int8 330MB + tokenizer.json"

  注意：tag 名（models-v1）要与 .github/workflows/desktop-release.yml 里的
  MODELS_RELEASE_TAG 一致。换模型时**新开一个 tag**（models-v2），不要覆盖旧
  release —— 已发布的安装包要能追溯它当时用的是哪份物料。

归档内容：
  模型主文件 + 外置权重（若该变体有）+ tokenizer.json + MANIFEST.txt
  全部平铺在归档根，CI 解开即可直接作为 --model-src。
`.trim();

const log = (msg) => console.log(msg);
const fail = (msg) => {
  console.error(`\n打包失败：${msg}\n`);
  process.exit(1);
};

function parseArgs(argv) {
  const opts = {
    variant: 'int8',
    src: null,
    out: null,
    keepStage: false
  };
  for (const a of argv) {
    if (a.startsWith('--variant=')) {
      opts.variant = a.slice('--variant='.length).trim();
      if (!opts.variant) fail('--variant= 后面要写变体名，例如 --variant=int8');
    } else if (a.startsWith('--src=')) {
      const v = a.slice('--src='.length).trim();
      if (!v) fail('--src= 后面要写目录路径');
      // 相对路径按调用时的 cwd 解析 —— 用户可能在仓库外调用。
      opts.src = path.resolve(v);
    } else if (a.startsWith('--out=')) {
      const v = a.slice('--out='.length).trim();
      if (!v) fail('--out= 后面要写文件路径');
      opts.out = path.resolve(v);
    } else if (a === '--keep-stage') {
      opts.keepStage = true;
    } else if (a === '--help' || a === '-h') {
      console.log(HELP);
      process.exit(0);
    } else {
      fail(`未知参数：${a}（用 --help 查看用法）`);
    }
  }
  return opts;
}

/**
 * 流式算 sha256。330MB 不要整个读进内存。
 *
 * ⚠ 必须等 `'close'` 而不是 `'end'` 才 resolve：`'end'` 只表示数据读完，
 *   文件句柄要到 `'close'` 才释放。Windows 上句柄未释放时另一个进程打开
 *   同一文件会拿到 EBUSY —— 本脚本对每个成员算完摘要后紧接着 spawn tar
 *   去读同一批文件，正好是这个组合（verify-models-archive.mjs 里实测复现过）。
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
 * 用系统 tar 打扁平归档。
 *
 * 为什么调外部 tar 而不是在 Node 里手写 tar 格式：这只是构建工具，不是运行时代码，
 * 而三个平台的 tar（GNU / bsdtar）都成熟可靠。手写 tar 要处理的
 * header 校验和、长文件名、USTAR 边界都不值得。
 *
 * `-C <stageDir>` 之后再列文件名，归档里就是平铺的 —— CI 解开即可用，
 * 不必猜目录层级。
 *
 * ⚠ Windows + GNU tar 的坑（实测踩过）：
 *   `-f` 的值里一旦出现带盘符的绝对路径，tar 会按 **host:path 远程归档语法**
 *   解析 `D:`，把 "D" 当主机名，然后报
 *       tar (child): Cannot connect to D: resolve failed
 *       tar: Error is not recoverable
 *   这个报错完全看不出"是路径写法的问题"。所以输出必须传**相对文件名**，
 *   靠 cwd 兜住位置（-C 的参数不做 host 解析，但照样换成正斜杠，
 *   免得 msys2 版 tar 在反斜杠上产生歧义）。
 */
function makeArchive(out, stageDir, names, compress) {
  const slash = (p) => p.replace(/\\/g, '/');
  const args = [
    compress ? '-czf' : '-cf',
    path.basename(out),          // ← 绝不能换成 out（绝对路径），见上面那段
    '-C', slash(stageDir),
    ...names
  ];
  const r = spawnSync('tar', args, {
    cwd: path.dirname(out),
    stdio: ['ignore', 'inherit', 'pipe'],
    encoding: 'utf8'
  });
  if (r.error) {
    fail(
      `无法执行 tar：${r.error.message}\n` +
      '  Windows 10+ / Linux / macOS 都自带 tar，不该缺；若是精简镜像请先装 bsdtar 或 GNU tar。'
    );
  }
  if (r.status !== 0) {
    fail(`tar 退出码 ${r.status}\n${(r.stderr || '').trim()}`);
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));

  const srcDir = opts.src || defaultModelSrc(REPO_ROOT);
  const compress = !(opts.out && opts.out.endsWith('.tar'));
  const out = opts.out || path.join(
    REPO_ROOT, 'dist-models', `miniagent-models-${opts.variant}${compress ? '.tar.gz' : '.tar'}`
  );

  log('内联 embedding 物料打包');
  log(`  变体：${opts.variant}`);
  log(`  来源：${srcDir}`);
  log(`  输出：${out}`);

  // ── 1. 校验（与构建期同一套规则）────────────────────────────────
  let plan;
  let tokenizerFrom;
  try {
    ({ plan, tokenizerFrom } = resolveModelSources({
      variant: opts.variant,
      srcDir,
      fallbackTokenizerSrc: defaultTokenizerSrc(REPO_ROOT)
    }));
  } catch (e) {
    if (e instanceof ModelSourceError) fail(e.message);
    throw e;
  }

  log(`\n[1/3] 校验物料（${plan.length} 项，解压后 ${mb(planBytes(plan))}）`);
  for (const p of plan) {
    log(`      ${p.name}  ${mb(p.bytes)}  [${p.role}]`);
  }
  if (tokenizerFrom === 'fallback') {
    log('      ↑ tokenizer 来自 HF 模型目录（物料目录里没有）——');
    log('        归档会把它一起收进去，这样 CI 只需一份归档即可备齐物料。');
  }

  // ── 2. 暂存 ────────────────────────────────────────────────────
  // 为什么要先全部拷进暂存目录，而不是直接 `tar -C srcDir <names>`：
  //   ① tokenizer 可能来自 fallback 路径，根本不在 srcDir 里，直接打包会漏；
  //   ② 归档成员的字节数在打包前可再核一次 —— 磁盘满导致 cp 截断是静默的，
  //      而截断的模型正是最难在客户机上定位的那类故障。
  const stageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-models-'));
  try {
    log(`\n[2/3] 暂存到 ${stageDir}`);
    for (const p of plan) {
      const dest = path.join(stageDir, p.name);
      fs.copyFileSync(p.from, dest);
      const copied = fs.statSync(dest).size;
      if (copied !== p.bytes) {
        fail(
          `暂存复制后字节数不符：${p.name}\n` +
          `  期望 ${p.bytes.toLocaleString('en-US')} B，实际 ${copied.toLocaleString('en-US')} B\n` +
          '  多半是磁盘空间不足导致的截断。归档里绝不能出现半份模型 ——\n' +
          '  解压后一样能通过"文件存在"检查，却会在建会话时失败。'
        );
      }
    }

    // MANIFEST.txt 是给排查用的：客户机上解开归档，一眼能看出这是哪份物料。
    // 不放归档自身的 sha256（那时还没算出来），只放各成员的。
    const lines = [
      `variant: ${opts.variant}`,
      `source:  ${srcDir}`,
      `packed:  ${new Date().toISOString()}`,
      '',
      ...plan.map((p) => `${p.bytes.toString().padStart(12, ' ')} B  ${p.name}  [${p.role}]`),
      ''
    ];
    for (const p of plan) {
      lines.push(`${await sha256File(path.join(stageDir, p.name))}  ${p.name}`);
    }
    lines.push('');
    fs.writeFileSync(path.join(stageDir, 'MANIFEST.txt'), lines.join('\n'), 'utf8');

    const names = [...plan.map((p) => p.name), 'MANIFEST.txt'];

    // ── 3. 打包 + 摘要 ───────────────────────────────────────────
    log(`\n[3/3] ${compress ? 'tar.gz' : 'tar'} 打包（${names.length} 个成员）`);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    rmIfExists(out);
    makeArchive(out, stageDir, names, compress);

    const outBytes = fs.statSync(out).size;
    const sha = await sha256File(out);
    const shaFile = `${out}.sha256`;
    // sha256sum 的格式：<hex>  <文件名>（两个空格）。写 basename 而不是全路径，
    // 这样 CI 上 cd 到下载目录后可以直接 `sha256sum -c`。
    fs.writeFileSync(shaFile, `${sha}  ${path.basename(out)}\n`, 'utf8');

    log('');
    log(`      归档  ${mb(outBytes)}  ${out}`);
    log(`      摘要  ${sha}`);
    log(`      sha256 文件  ${shaFile}`);
    log('');
    log(`完成。发布给 CI：`);
    log(`  gh release create models-v1 \\`);
    log(`     "${out}" "${shaFile}" \\`);
    log(`     --title "models-v1（内联 embedding 物料）" --notes "${opts.variant}"`);
    log('');
  } finally {
    if (opts.keepStage) {
      log(`暂存目录保留：${stageDir}`);
    } else {
      fs.rmSync(stageDir, { recursive: true, force: true });
    }
  }
}

function rmIfExists(p) {
  fs.rmSync(p, { force: true });
}

main().catch((e) => {
  console.error(`\n打包异常终止：${e && e.stack ? e.stack : e}\n`);
  process.exit(1);
});
