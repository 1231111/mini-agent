/**
 * 静态校验 .github/workflows/desktop-release.yml。
 *
 * 为什么需要它：本机 GitHub 直连不通，这条 workflow 从来没法真跑一次。
 * 而四个平台的包里，Linux / macOS 那两份**只能**靠它产出 —— 配置写错要等到
 * 打标签发布时才发现，代价很高。所以在这里把能静态查的全查一遍。
 *
 * 覆盖：
 *   1. YAML 本身可解析（缩进 / 语法）
 *   2. 矩阵四行齐备，字段齐全
 *   3. os 标签 与 arch 声明自洽（标签不绑架构，靠这张表钉住）
 *   4. artifact 名与 package.json 的 artifactName 口径一致
 *   5. Build 步骤同时传了 --target 与 --expect-arch，且取值来自矩阵
 *   6. upload-artifact 的 path 通配符覆盖各目标平台实际会产出的扩展名
 *   7. 模型物料链路：归档名与变体名在 workflow / pack-models / build-desktop
 *      三处一致，且下载步骤的时机、取料方式、落地目录都对
 *
 * 用法：
 *   NODE_PATH=mini-agent-desktop/node_modules node .verify/check-workflow.mjs
 * （js-yaml 来自 mini-agent-desktop 的传递依赖，不额外装包）
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const yaml = require('js-yaml');

const WF = '.github/workflows/desktop-release.yml';
const PKG = 'mini-agent-desktop/package.json';

let failed = 0;
const ok = (m) => console.log(`[PASS] ${m}`);
const bad = (m) => { failed++; console.log(`[FAIL] ${m}`); };
const check = (cond, m) => (cond ? ok(m) : bad(m));

/* ───────── 1. 解析 ───────── */
const raw = fs.readFileSync(WF, 'utf8');
let doc;
try {
  doc = yaml.load(raw, { schema: yaml.JSON_SCHEMA });
  ok(`${WF} 可解析为 YAML`);
} catch (e) {
  bad(`YAML 解析失败：${e.message}`);
  process.exit(1);
}

// YAML 1.1 的解析器会把裸 on: 当成布尔 true，两种都认
const triggers = doc.on ?? doc.true;
check(!!triggers, '存在 on: 触发器段');
// 注意用 in 而不是取值判真假：`workflow_dispatch:` 后面不带值，YAML 解析出来是 null，
// 但键是存在的 —— 这正是 GitHub Actions 里「只要有这个触发器」的标准写法。
check(triggers && 'workflow_dispatch' in triggers, '支持手动触发（workflow_dispatch）');
check(!!triggers?.push?.tags?.length, '支持打标签触发（push.tags）');

/* ───────── 2. 矩阵结构 ───────── */
const job = doc.jobs?.desktop;
check(!!job, '存在 jobs.desktop');
check(job?.strategy?.['fail-fast'] === false, 'strategy.fail-fast=false（一个平台失败不连坐）');

const rows = job?.strategy?.matrix?.include;
check(Array.isArray(rows), 'matrix.include 是数组');
check(rows?.length === 4, `矩阵正好 4 行（实际 ${rows?.length}）`);

const FIELDS = ['os', 'label', 'target', 'arch', 'artifact'];
for (const [i, r] of (rows || []).entries()) {
  const missing = FIELDS.filter((f) => !r[f]);
  if (missing.length) bad(`矩阵第 ${i + 1} 行缺字段：${missing.join(', ')}`);
}
if (!failed) ok('四个矩阵行都含 os / label / target / arch / artifact');

/* ───────── 3. os 标签 ↔ arch 自洽 ───────── */
// 标签与架构没有绑定关系，这张表就是那层绑定。改矩阵时必须同步改这里。
const EXPECT = {
  'windows-latest': 'x64',
  'ubuntu-latest': 'x64',
  'ubuntu-24.04-arm': 'arm64',
  'macos-latest': 'arm64'
};
for (const r of rows || []) {
  const want = EXPECT[r.os];
  if (!want) { bad(`出现了未登记的 runner 标签：${r.os}（请先确认它的架构，再补进 EXPECT）`); continue; }
  check(r.arch === want, `${r.os} → arch=${r.arch}（登记值 ${want}）`);
}

/* ───────── 4. artifact 名与产物命名口径一致 ───────── */
const pkg = JSON.parse(fs.readFileSync(PKG, 'utf8'));
const tmpl = pkg.build?.artifactName || '';
check(tmpl.includes('${os}') && tmpl.includes('${arch}'),
  `package.json artifactName 含 \${os} 与 \${arch}：${tmpl}`);
for (const r of rows || []) {
  check(r.artifact === `MiniAgent-${r.target}-${r.arch}`,
    `artifact 名与产物口径一致：${r.artifact}（期望 MiniAgent-${r.target}-${r.arch}）`);
}

/* ───────── 5. Build 步骤传参 ───────── */
const steps = job?.steps || [];
const buildStep = steps.find((s) => s.name === 'Build desktop package');
check(!!buildStep, '存在 Build desktop package 步骤');
const cmd = buildStep?.run || '';
check(cmd.includes('--target=${{ matrix.target }}'), 'Build 步骤用矩阵的 target 传 --target');
check(cmd.includes('--expect-arch=${{ matrix.arch }}'), 'Build 步骤用矩阵的 arch 传 --expect-arch');

/* ───────── 6. 上传通配符覆盖各目标的扩展名 ───────── */
// 依据 package.json 的 build.<target>.target
const EXTS = {
  win: (pkg.build?.win?.target || []).map((t) => (t === 'portable' || t === 'nsis' ? '.exe' : `.${t}`)),
  mac: (pkg.build?.mac?.target || []).map((t) => `.${t}`),
  linux: (pkg.build?.linux?.target || []).map((t) => `.${t}`)
};
const upStep = steps.find((s) => s.name === 'Upload artifact');
check(!!upStep, '存在 Upload artifact 步骤');
const pathSpec = String(upStep?.with?.path || '');
check(upStep?.with?.['if-no-files-found'] === 'error',
  'if-no-files-found=error（没产物就让 job 失败，不静默通过）');
check(/^v\d+$/.test(String(upStep?.uses || '').split('@')[1] || ''),
  `upload-artifact 用了带版本号的 tag：${upStep?.uses}`);
check(/^v\d+$/.test(String(steps.find((s) => s.name === 'Set up JDK 21')?.uses || '').split('@')[1] || ''),
  'setup-java 用了带版本号的 tag');

for (const [t, exts] of Object.entries(EXTS)) {
  for (const e of exts) {
    check(pathSpec.includes(`*${e}`), `上传通配符覆盖 ${t} 的 ${e}（build.${t}.target）`);
  }
  if (!exts.length) bad(`package.json 里 build.${t}.target 为空，无法推出产物扩展名`);
}

/* ───────── 7. 模型物料链路 ───────── */
// 这一段堵的是同一类错：**归档名 / 变体名在三处各写了一遍，改一处漏一处**。
// 它不会报错，只会让 CI 在第 5 步说"release 里找不到这个文件"——
// 而文件明明就在，只是名字差一个字符，排查起来很费神。
const wfEnv = doc.env || {};
check(!!wfEnv.MODELS_RELEASE_TAG, `env.MODELS_RELEASE_TAG 已定义：${wfEnv.MODELS_RELEASE_TAG}`);
check(!!wfEnv.MODELS_ARCHIVE, `env.MODELS_ARCHIVE 已定义：${wfEnv.MODELS_ARCHIVE}`);

const archiveName = String(wfEnv.MODELS_ARCHIVE || '');
// 归档名必须与 scripts/pack-models.mjs 的默认输出名逐字一致。
// 那边的命名规则是 `miniagent-models-${variant}.tar.gz`；改了要同步这里。
check(archiveName === 'miniagent-models-int8.tar.gz',
  `MODELS_ARCHIVE 与 pack-models.mjs 的默认输出名一致：${archiveName || '(空)'}`);

// 归档里的变体必须与 build-desktop.mjs 的默认 --model-variant 相同。
// 不一致时 CI 必然在第 5 步失败：fp16 / fp32 的权重是外置的，归档只含导出时
// 被选中的那一份，构建却去要另一份的 .data。
const buildSrc = fs.readFileSync('scripts/build-desktop.mjs', 'utf8');
const defaultVariant = (buildSrc.match(/modelVariant:\s*'([^']+)'/) || [])[1];
check(!!defaultVariant, `从 build-desktop.mjs 抓到默认变体：${defaultVariant || '(没抓到)'}`);
if (defaultVariant) {
  check(archiveName.includes(`-${defaultVariant}.`),
    `MODELS_ARCHIVE 的变体与构建默认变体一致（都是 ${defaultVariant}）`);
}

const dlIdx = steps.findIndex((s) => s.name === 'Download model artifacts');
const buildIdx = steps.findIndex((s) => s.name === 'Build desktop package');
const coIdx = steps.findIndex((s) => String(s.uses || '').startsWith('actions/checkout'));
check(dlIdx >= 0, '存在 Download model artifacts 步骤');
check(coIdx >= 0 && dlIdx > coIdx, '物料下载在 checkout 之后');
check(dlIdx >= 0 && buildIdx >= 0 && dlIdx < buildIdx, '物料下载在 Build desktop package 之前');

const dlStep = steps[dlIdx] || {};
const dlRun = String(dlStep.run || '');
// gh CLI 而不是 curl：私有仓库的 release asset 匿名 URL 取不到（404），
// gh 会带 GITHUB_TOKEN 走 API，公开 / 私有都能用。
check(!!dlStep.env?.GH_TOKEN, '下载步骤带了 GH_TOKEN');
check(dlRun.includes('gh release download'), '用 gh CLI 取 release asset');
// 一次 --pattern 同时下归档与 .sha256：分两次会出现"校验文件是新的、归档是旧的"。
check(dlRun.includes('${MODELS_ARCHIVE}*'), '--pattern 一次取到归档与 .sha256');
check(dlRun.includes('verify-models-archive.mjs'), '解包前先校验 sha256');

// ⚠ 下面两条踩过一次「假绿」，值得记下来：
//   原先写的是 dlRun.includes('< /dev/null') 和 dlRun.includes('.verify/onnx')，
//   结果把命令行里的 `< /dev/null` 删掉，校验照样报 PASS —— 因为**这段 run 块
//   自己带的注释里就写着这几个字**（YAML 的 literal block 里注释也是字符串内容）。
//   凡是"文本包含"型断言，都要防着注释/文档满足它。
//   所以先按行把真正的命令行抓出来，再对那一行断言：注释行以 # 开头，
//   不会命中 `^[ \t]*tar `。
const tarLine = (dlRun.match(/^[ \t]*tar [^\n]*$/m) || [''])[0].trim();
check(!!tarLine, `定位到 tar 解包命令行${tarLine ? `：${tarLine}` : '（没找到）'}`);
check(tarLine.includes('"$WORK/${MODELS_ARCHIVE}"') && tarLine.includes('-C .verify/onnx'),
  'tar 的 -f 用相对路径（GNU tar 会把 D: 当主机名）、-C 指向 .verify/onnx');
check(tarLine.includes('< /dev/null'),
  'tar 加了 < /dev/null（msys2 tar 在 stdin 是匿名管道时会 EBUSY）');

/* ───────── 结果 ───────── */
console.log();
if (failed) {
  console.log(`失败 ${failed} 项`);
  process.exit(1);
}
console.log('全部通过');
