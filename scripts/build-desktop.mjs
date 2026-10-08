#!/usr/bin/env node
/**
 * MiniAgent 桌面客户端构建脚本（跨平台唯一入口）。
 *
 * 替代原 scripts/build-desktop.ps1 —— 那份只能在 Windows 上跑，
 * 而 jlink 与 electron-builder 都必须在目标平台上执行，所以构建入口本身
 * 必须先做到三平台通用。
 *
 * 六步：
 *   1. mvnw package       —— 打 fat jar（spring-boot-maven-plugin 已配 repackage）
 *   2. jlink              —— 用本机 JDK 裁一个最小 JRE 到 mini-agent-desktop/jre
 *   3. Electron ffmpeg    —— 下含 H.264/AAC 的 ffmpeg 到 mini-agent-desktop/ffmpeg
 *   4. playwright CLI     —— 把 Chromium 下到 mini-agent-desktop/browsers（随包分发）
 *   5. 复制模型           —— ONNX 变体与 tokenizer.json 放进 mini-agent-desktop/models
 *   6. electron-builder   —— 出本平台的安装包
 *
 * 用法：
 *   node scripts/build-desktop.mjs
 *   node scripts/build-desktop.mjs --skip-jar              # 复用已打好的 fat jar
 *   node scripts/build-desktop.mjs --skip-jre              # 复用已有 jre
 *   node scripts/build-desktop.mjs --skip-ffmpeg           # 复用已下的 ffmpeg
 *   node scripts/build-desktop.mjs --skip-browsers         # 复用已下的 Chromium
 *   node scripts/build-desktop.mjs --skip-models           # 复用已复制好的 models
 *   node scripts/build-desktop.mjs --skip-package          # 只准备物料，不出包
 *   node scripts/build-desktop.mjs --model-variant=fp32    # 换模型变体（默认 int8）
 *   node scripts/build-desktop.mjs --expect-arch=arm64     # 声明机器架构，不符即失败（CI 用）
 *   node scripts/build-desktop.mjs --target=win            # 声明当前平台，不符即失败（CI 用）
 *
 * 为什么第 4 步默认只下 headless shell（省 314MB）：
 *   Playwright 1.49 起把 Chromium 拆成两个包，选哪个由 headless 选项决定 ——
 *   playwright-core 的 lib/server/chromium/chromium.js 里就一行：
 *       getExecutableName(options) {
 *         if (options.channel) return options.channel;
 *         return options.headless ? 'chromium-headless-shell' : 'chromium';
 *       }
 *   BrowserService 传的是 agent.browser.headless（默认 true，桌面档与 prod 档都写死 true，
 *   且 ProductionReadinessValidator 会在 prod 下拒绝 headless=false），也没有 setChannel()，
 *   所以完整的 chromium 包（314MB）在出厂包里永远不会被加载。
 *   实测：把 chromium-<rev> 目录改名藏起来，headless=true 仍能正常渲染页面；
 *   同一份目录下 headless=false 则会报
 *   "Executable doesn't exist at ...chromium-<rev>/chrome-win/chrome.exe"。
 *   需要留出有头调试能力时加 --with-headed-chromium（两个包都下，约 +314MB）。
 *
 * 为什么 Chromium 走随包分发而不是首启下载：
 *   出厂形态的客户机可能完全离线，而 BrowserService.resolvePlaywrightJarPath() 在
 *   fat jar 布局下拿不到嵌套 jar 的真实路径（java.class.path 只有 fat jar 自己），
 *   所以"运行时自动安装"这条兜底在出厂形态下大概率失败。物料必须在构建期落盘。
 *
 * 为什么模型也走随包分发：
 *   理由同上（离线客户机上"首启下载 330MB"必然失败），但风险更高一层：
 *   模型缺失不会让应用起不来，只会让 LocalOnnxEmbeddingModel 降级成
 *   "没有向量的检索"，症状是"检索结果不准"—— 很难和"安装包少了个文件"联系起来。
 *   所以这里不止复制，还按 manifest.json 的字节数卡一道：截断的模型同样能通过
 *   文件存在性检查，却会在建会话时失败。
 *
 * 为什么默认 int8 而不是 fp16：
 *   三档实测：
 *     变体   字节数        建会话(Java) 短文本(~30字) 长文本(~400字) 真实改写检索 top-1 / top-3 / MRR
 *     fp32   1,307,989,980    5290ms     56.00ms      291.64ms     9/10 / 10/10 / 0.950
 *     fp16     655,377,306    4883ms     70.59ms      357.80ms     9/10 / 10/10 / 0.950
 *     int8     329,744,186    4746ms     31.92ms      132.81ms     9/10 / 10/10 / 0.950
 *   fp16 在 CPU 上比 fp32 还慢（ORT 的 CPU EP 没有原生 fp16 kernel，要多插 Cast 节点），
 *   却只省一半体积 —— 两头不占。int8 体积是 fp32 的 1/4，真实改写检索的排序与 fp32
 *   逐项相同。
 *
 *   注意「建会话」是 Java 侧（出厂路径），三档只差 11% —— Java ORT 有约 4.5 秒的固定
 *   初始化开销在主导，同一份 int8 在 Python 侧只要 0.53 秒。「短/长文本」是 Python 侧
 *   口径，Java 侧未测；两边不能互相推算，所以后者不作出厂保证。
 *   int8 的退化面集中在无实义文本（纯标点、空串、罕见字），那些在检索里本来
 *   就不是有效 query；逐条 cosine 最小值 0.9811，样本互查的全序一致率 86.1%。
 *   依据：.verify/onnx/rank-fidelity.json 与 rank_fidelity.py。
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

import {
  ModelSourceError,
  defaultModelSrc,
  defaultTokenizerSrc,
  resolveModelSources
} from './lib/model-manifest.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const DESKTOP_DIR = path.join(REPO_ROOT, 'mini-agent-desktop');
const JRE_DIR = path.join(DESKTOP_DIR, 'jre');
const FFMPEG_DIR = path.join(DESKTOP_DIR, 'ffmpeg');
const BROWSERS_DIR = path.join(DESKTOP_DIR, 'browsers');
const MODELS_DIR = path.join(DESKTOP_DIR, 'models');
const JAR_PATH = path.join(REPO_ROOT, 'mini-agent-app', 'target', 'mini-agent-app-0.0.1-SNAPSHOT.jar');

const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';

/**
 * 必须与 mini-agent-desktop/node_modules/electron 的实际版本一致。
 *
 * ffmpeg 是 Electron 的私有构建产物，版本错配的后果不是报错而是运行期崩溃
 * （Chromium 加载外部 ffmpeg 时会校验），所以这里要显式对齐。
 * 改 electron 版本时记得同步这一行。
 */
const ELECTRON_VERSION = '28.3.3';

/** Electron release 里 ffmpeg 包内的文件名。 */
const FFMPEG_FILE = {
  win32: 'ffmpeg.dll',
  darwin: 'libffmpeg.dylib',
  linux: 'libffmpeg.so'
};

/**
 * 物料清单（各变体的文件名与字节数、tokenizer 字节数、校验规则）定义在
 * scripts/lib/model-manifest.mjs，本文件只是使用方之一。
 *
 * 为什么不在就地定义：scripts/pack-models.mjs 要把同一批物料打成归档给 CI 用，
 * 两边必须走**同一套**闸门。两份定义 = 改一处漏一处，结果是「打包放行、构建拦住」
 * 这种自相矛盾的状态，而它能一直潜伏到有人重新导出模型才爆。
 */
const MODEL_SRC_DEFAULT = defaultModelSrc(REPO_ROOT);

/**
 * tokenizer.json 的**兜底**来源：HF 原始模型目录（它跟 HF 模型放在一起，
 * 同样不入版本控制，所以本机没下过模型时会找不到）。
 *
 * 注意这只是兜底：只要 --model-src 指向的目录里自带 tokenizer.json，就用那个。
 * 出厂路径（CI 下载物料归档后解开）走的就是自带那份 —— 模型与 tokenizer
 * 装在一起，版本不可能错配。
 */
const TOKENIZER_SRC = defaultTokenizerSrc(REPO_ROOT);

const HELP = `
MiniAgent Desktop 构建脚本（跨平台）

用法：
  node scripts/build-desktop.mjs [选项]

选项：
  --skip-jar               复用已打好的 fat jar
  --skip-jre               复用已裁好的 mini-agent-desktop/jre
  --skip-ffmpeg            复用已下载的 mini-agent-desktop/ffmpeg
  --skip-browsers          复用已下载的 mini-agent-desktop/browsers
  --skip-models            复用已复制好的 mini-agent-desktop/models
  --skip-package           只准备物料，不出安装包
  --with-headed-chromium   连完整 Chromium 一起下（+314MB，仅有头调试需要）
  --model-variant=int8     随包模型变体：int8（默认，330MB）/ fp16（655MB）/ fp32（1308MB）
  --model-src=<dir>        ONNX 变体所在目录（默认 .verify/onnx，由 export_onnx.py 产出）
  --expect-arch=arm64      声明这台机器应有的架构，不符就立刻失败（CI 用）
  --target=win|mac|linux   声明当前平台，与机器不符就立刻失败（CI 用；只能写一个）
  -h, --help               显示本帮助

约束：
  jlink 与 Electron 都无法交叉产出 —— 四个平台各自需要一台对应架构的机器，
  各跑一次（CI 里是同一条 workflow 的四行矩阵）：
    Windows x64     node scripts/build-desktop.mjs --target=win   --expect-arch=x64
    Linux x64       node scripts/build-desktop.mjs --target=linux --expect-arch=x64
    Linux arm64     node scripts/build-desktop.mjs --target=linux --expect-arch=arm64
    macOS Apple Si  node scripts/build-desktop.mjs --target=mac   --expect-arch=arm64

  不能一条命令出四个包：jlink 按当前机器裁 JRE，而 JRE 是平台专有的，
  跨平台出包只会塞进去一份跑不起来的 JRE。所以 --target 只接受当前平台。
  同理 macOS Intel（x64）不受支持：DJL tokenizers 没有 osx-x86_64 原生库，
  内联 embedding 在 Intel Mac 上会静默降级。

  --target / --expect-arch 的作用是堵「跑错机器」这个静默错误：构建链路上的
  jlink 与 electron-builder 都只按当前机器出包，不会核对你以为在打什么；
  而 CI 里 artifact 的名字是手写的，与 runner 架构没有绑定。传上期望值后，
  不一致会在第一步就被拦住，而不是产出一份名实不符的包。

物料来源（模型与 Chromium 都不入版本控制，构建期必须落进工作区）：
  模型      --model-src（默认 .verify/onnx/）里取三样：<变体>.onnx、它的外置权重
            .data（int8 没有）、tokenizer.json。目录里没有 tokenizer.json 时，
            才退到 embedding-server 的 HF 模型目录去取（本机开发路径）。
  Chromium  由 Playwright CLI 现下

  两类物料缺失都直接失败，不静默放过 —— 出厂包里少任何一样，症状都是
  "应用能启动、只是某个功能不工作"，在客户机上极难定位。

  CI 上不要现导出模型：ONNX 变体是平台无关的确定性产物，四个平台共用同一份，
  在 runner 里各跑一遍导出只会换来四个可能不同的结果。正确做法是在任一有网的
  机器上导出一次，打成归档传到 release：
    node scripts/pack-models.mjs --variant=int8
  归档（205MB）里同时含模型与 tokenizer.json，解开即可直接作为 --model-src。
  下载与校验见 .github/workflows/desktop-release.yml 的 Download model artifacts。

  本机备齐模型物料的两步（两处源头都不入版本控制）：
    1) HF 原始模型（提供 tokenizer.json），目标路径：
         embedding-server/models/models/IEITYuan--Yuan-embedding-2.0-zh/snapshots/master/
       仓库里的 embedding-server/fetch_modelscope.sh 是容器内脚本，它的
       cache_dir=/models 对应宿主机就是这个目录 —— 在容器里跑它即可落到位。
       注意 entrypoint.sh 走的是 HF 缓存（embedding-server/models/hf），不是这里。
    2) 导出 ONNX 变体。--variants 是开关，不带值；它会同时导 fp16 与 int8：
         python .verify/onnx/export_onnx.py --variants
       产出落在 .verify/onnx/，也就是 --model-src 的默认值。
`.trim();

/**
 * jlink 模块清单。
 *
 * 保守超集：多带一个模块通常只有几百 KB，而漏一个会让出厂包在某条冷路径上
 * 直接 NoClassDefFoundError —— 那种错在客户机上极难定位。
 *
 *   java.net.http     —— langchain4j-http-client-jdk 走 JDK HttpClient
 *   jdk.crypto.ec     —— HTTPS 的 ECDHE 密钥交换，缺了所有 https 调用都握手失败
 *   jdk.unsupported   —— sun.misc.Unsafe，Netty / 若干字节码库依赖
 *   java.naming       —— Spring 的 JNDI 支持
 *   java.instrument   —— Spring 的 LoadTimeWeaver
 *   java.sql + rowset —— JPA / JDBC / H2
 *   java.desktop      —— java.awt 相关（无头模式下部分图像 / 字体 API 仍会引用）
 */
const JRE_MODULES = [
  'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging',
  'java.management', 'java.naming', 'java.net.http', 'java.prefs', 'java.rmi',
  'java.scripting', 'java.security.jgss', 'java.security.sasl', 'java.sql',
  'java.sql.rowset', 'java.transaction.xa', 'java.xml', 'java.xml.crypto',
  'jdk.crypto.cryptoki', 'jdk.crypto.ec', 'jdk.httpserver', 'jdk.jfr',
  'jdk.management', 'jdk.unsupported', 'jdk.unsupported.desktop', 'jdk.zipfs'
].join(',');

/* ───────────────────────── 小工具 ───────────────────────── */

const TOTAL_STEPS = 6;
const log = (msg) => console.log(msg);
const step = (n, msg) => console.log(`\n[${n}/${TOTAL_STEPS}] ${msg}`);
const fail = (msg) => {
  console.error(`\n构建失败：${msg}\n`);
  process.exit(1);
};

/**
 * 起一个子进程并等待结束。
 *
 * Windows 上 .cmd / .bat 必须经过 shell 才能执行，而 Node 的 spawn 直连
 * CreateProcess 时不认批处理 —— 会报 ENOENT，看起来像"文件不存在"，
 * 实际是执行方式不对。这里按扩展名自动决定。
 */
function run(cmd, args, { cwd = REPO_ROOT, env = process.env } = {}) {
  const needsShell = IS_WIN && /\.(cmd|bat)$/i.test(cmd);
  const r = spawnSync(cmd, args, { cwd, env, stdio: 'inherit', shell: needsShell });
  if (r.error) {
    throw new Error(`无法执行 ${cmd}\n  ${r.error.message}`);
  }
  if (r.status !== 0) {
    throw new Error(`命令退出码 ${r.status}：${cmd} ${args.join(' ')}`);
  }
}

/** 递归统计目录体积。不要用 du —— 各平台的 du 行为与可用性都不一致。 */
function dirSize(dir) {
  let total = 0;
  const walk = (d) => {
    let entries;
    try {
      entries = fs.readdirSync(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.isFile()) {
        try { total += fs.statSync(p).size; } catch { /* 竞态删除，忽略 */ }
      }
    }
  };
  walk(dir);
  return total;
}

const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

function rmrf(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

/* ───────────────────────── 极简 zip 读取 ───────────────────────── */

/**
 * 列出 zip 里的所有条目名。
 *
 * 为什么不调 unzip / Expand-Archive：这三个工具在四个目标平台上的可用性不一致
 * （Windows CI 的 PowerShell 有 Expand-Archive 但没 unzip，Linux 反过来，
 * macOS 的 unzip 又是老版本），而这里只需要读一个小 zip 里的单个文件。
 * zip 结构本身足够简单：EOCD → 中央目录 → 本地头。
 */
function unzipList(zip) {
  // EOCD 在文件尾部，后面可能有最长 65535 字节的注释，所以要往前找签名
  let eocd = -1;
  const minPos = Math.max(0, zip.length - 22 - 65535);
  for (let i = zip.length - 22; i >= minPos; i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是有效的 zip：找不到 EOCD 记录（文件可能被截断）');

  const entryCount = zip.readUInt16LE(eocd + 10);
  const entries = [];
  let p = zip.readUInt32LE(eocd + 16);

  for (let i = 0; i < entryCount; i++) {
    if (p + 46 > zip.length || zip.readUInt32LE(p) !== 0x02014b50) break;
    const method = zip.readUInt16LE(p + 10);
    const compSize = zip.readUInt32LE(p + 20);
    const nameLen = zip.readUInt16LE(p + 28);
    const extraLen = zip.readUInt16LE(p + 30);
    const commentLen = zip.readUInt16LE(p + 32);
    const localOffset = zip.readUInt32LE(p + 42);
    const name = zip.toString('utf8', p + 46, p + 46 + nameLen);
    if (!name.endsWith('/')) entries.push({ name, method, compSize, localOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** 从 zip 的 Buffer 里取出一个条目并解压成 Buffer（只适合小 zip）。 */
function unzipEntry(zip, entry) {
  const { start } = localDataRange(zip, entry);
  const raw = zip.subarray(start, start + entry.compSize);

  if (entry.method === 0) return Buffer.from(raw);          // stored
  if (entry.method === 8) return zlib.inflateRawSync(raw);  // deflate
  throw new Error(`不支持的压缩方式 ${entry.method}（仅支持 store / deflate）`);
}

/**
 * 按本地文件头算出数据段的起始偏移。
 *
 * 不能直接用中央目录里记的 compSize 配 localOffset —— 本地头的 extra 字段长度
 * 与中央目录里的可以不同（zip 规范允许），差多少就错多少字节。
 */
function localDataRange(buf, entry) {
  const nameLen = buf.readUInt16LE(entry.localOffset + 26);
  const extraLen = buf.readUInt16LE(entry.localOffset + 28);
  return { start: entry.localOffset + 30 + nameLen + extraLen };
}

/**
 * 读取磁盘上 zip 文件的条目清单。
 *
 * <p>只读文件尾部的 EOCD 与中央目录（几百 KB），不把整个文件读进内存 ——
 * 这里的典型输入是 508MB 的 Spring Boot fat jar，整个读进来既慢又没必要。
 *
 * <p>为什么不用 {@code jar tf} / {@code unzip -l}：两者都是"能跑最好、跑不了就卡住"
 * 的外部依赖，而且实测在某些受限环境下 spawn 会直接返回 EBUSY。
 * zip 的中央目录结构很简单，自己解既没有依赖也不会有环境差异。
 */
function listZipEntriesInFile(filePath) {
  const fd = fs.openSync(filePath, 'r');
  try {
    const total = fs.fstatSync(fd).size;

    // EOCD 固定在尾部，后面可能有最长 65535 字节的注释
    const tailLen = Math.min(total, 22 + 65535);
    const tail = Buffer.alloc(tailLen);
    fs.readSync(fd, tail, 0, tailLen, total - tailLen);

    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error(`不是有效的 zip（找不到 EOCD）：${filePath}`);

    const entryCount = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOffset = tail.readUInt32LE(eocd + 16);

    const cd = Buffer.alloc(cdSize);
    fs.readSync(fd, cd, 0, cdSize, cdOffset);

    const entries = [];
    let p = 0;
    for (let i = 0; i < entryCount; i++) {
      if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) break;
      const method = cd.readUInt16LE(p + 10);
      const compSize = cd.readUInt32LE(p + 20);
      const nameLen = cd.readUInt16LE(p + 28);
      const extraLen = cd.readUInt16LE(p + 30);
      const commentLen = cd.readUInt16LE(p + 32);
      const localOffset = cd.readUInt32LE(p + 42);
      const name = cd.toString('utf8', p + 46, p + 46 + nameLen);
      if (!name.endsWith('/')) entries.push({ name, method, compSize, localOffset });
      p += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * 把 zip 里的单个条目流式解压到磁盘。
 *
 * <p>必须流式：要抽的 driver-bundle 解开有 189MB，整块读进内存没有必要，
 * 而且真要遇到更大的文件会直接爆掉。
 */
async function extractZipEntryToFile(zipPath, entry, outPath) {
  const fd = fs.openSync(zipPath, 'r');
  let start;
  try {
    const lh = Buffer.alloc(30);
    fs.readSync(fd, lh, 0, 30, entry.localOffset);
    const nameLen = lh.readUInt16LE(26);
    const extraLen = lh.readUInt16LE(28);
    start = entry.localOffset + 30 + nameLen + extraLen;
  } finally {
    fs.closeSync(fd);
  }

  const end = start + entry.compSize - 1;
  const src = () => fs.createReadStream(zipPath, { start, end });

  if (entry.method === 0) {
    await pipeline(src(), fs.createWriteStream(outPath));
  } else if (entry.method === 8) {
    await pipeline(src(), zlib.createInflateRaw(), fs.createWriteStream(outPath));
  } else {
    throw new Error(`不支持的压缩方式 ${entry.method}（仅支持 store / deflate）：${entry.name}`);
  }
}

/* ───────────────────────── Maven / JDK 定位 ───────────────────────── */

/** Maven Wrapper：Windows 是 mvnw.cmd，其余是 mvnw。 */
function mvnwPath() {
  const p = path.join(REPO_ROOT, IS_WIN ? 'mvnw.cmd' : 'mvnw');
  if (!fs.existsSync(p)) fail(`找不到 Maven Wrapper：${p}`);
  if (!IS_WIN) {
    try { fs.chmodSync(p, 0o755); } catch { /* 已可执行 */ }
  }
  return p;
}

/**
 * 定位本机 JDK（必须含 jlink，JRE 不行）。
 *
 * JAVA_HOME 优先；没设就从 PATH 上的 java 反推。反推时必须 realpath ——
 * Linux / macOS 上 /usr/bin/java 是符号链接，直接取两级父目录会得到 /usr，
 * 那里没有 jlink。
 */
function resolveJdk() {
  const javaName = IS_WIN ? 'java.exe' : 'java';
  const candidates = [];

  if (process.env.JAVA_HOME) {
    candidates.push(path.normalize(process.env.JAVA_HOME.replace(/[/\\]+$/, '')));
  }

  const probe = spawnSync(IS_WIN ? 'where' : 'which', [javaName], { encoding: 'utf8' });
  if (probe.status === 0) {
    const first = String(probe.stdout).split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0];
    if (first) {
      let real = first;
      try { real = fs.realpathSync(first); } catch { /* 保留原值 */ }
      candidates.push(path.dirname(path.dirname(real)));
    }
  }

  for (const home of candidates) {
    const jlink = path.join(home, 'bin', IS_WIN ? 'jlink.exe' : 'jlink');
    if (fs.existsSync(jlink)) {
      return {
        home,
        jlink,
        java: path.join(home, 'bin', javaName)
      };
    }
  }

  fail(
    '找不到含 jlink 的 JDK 21。\n' +
    '  JAVA_HOME 当前=' + (process.env.JAVA_HOME || '(未设置)') + '\n' +
    '  需要完整 JDK（JRE 不含 jlink）。安装后设置 JAVA_HOME 指向 JDK 根目录再重试。'
  );
}

/* ───────────────────────── 步骤 1：fat jar ───────────────────────── */

function stepJar(skip) {
  step(1, '打包 fat jar');
  if (skip) {
    log('      已跳过（--skip-jar）');
  } else {
    run(mvnwPath(), ['-q', 'package', '-pl', 'mini-agent-app', '-am', '-DskipTests']);
  }
  if (!fs.existsSync(JAR_PATH)) {
    fail(
      `找不到 fat jar：${JAR_PATH}\n` +
      '  开发态请先执行：' + (IS_WIN ? 'mvnw.cmd' : './mvnw') + ' package -pl mini-agent-app -am -DskipTests'
    );
  }
  log(`      jar: ${mb(fs.statSync(JAR_PATH).size)}`);
}

/* ───────────────────────── 步骤 2：jlink ───────────────────────── */

function stepJre(skip) {
  step(2, '用 jlink 裁剪 JRE');
  if (skip) {
    if (!fs.existsSync(JRE_DIR)) fail(`--skip-jre 但 ${JRE_DIR} 不存在`);
    log('      已跳过（--skip-jre）');
    return;
  }

  const jdk = resolveJdk();
  log(`      JDK: ${jdk.home}`);

  // jlink 要求 --output 指向一个不存在的目录，存在就直接报错。
  rmrf(JRE_DIR);
  run(jdk.jlink, [
    '--add-modules', JRE_MODULES,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=zip-6',
    '--output', JRE_DIR
  ]);

  log(`      JRE: ${mb(dirSize(JRE_DIR))}（本平台 ${process.platform}/${process.arch}）`);
  log('      注意：jlink 产物是平台专有的，这个目录不能跨平台复用，也不要提交到版本控制。');
}

/* ───────────────────────── 步骤 3：Electron ffmpeg ───────────────────────── */

/**
 * 下载 Electron 官方发布的「含专有编解码器」ffmpeg。
 *
 * Electron 自带的 ffmpeg 剥掉了 H.264 / AAC（专利原因），而本项目的生视频、
 * 生歌工具产出 mp4 / m4a，前端用 <video>/<audio> 播放时会黑屏或静音。
 *
 * 落盘后由 mini-agent-desktop/after-pack.cjs 在打包阶段换进产物 ——
 * 四个平台的落点不同（macOS 要钻进 Framework 内部），配置项表达不了。
 */
async function stepFfmpeg(skip) {
  step(3, '下载 Electron 专有编解码器 ffmpeg');

  const inner = FFMPEG_FILE[process.platform];
  if (!inner) fail(`未知平台 ${process.platform}，无法确定 ffmpeg 文件名`);

  if (skip) {
    if (!fs.existsSync(path.join(FFMPEG_DIR, inner))) {
      fail(`--skip-ffmpeg 但 ${path.join(FFMPEG_DIR, inner)} 不存在`);
    }
    log('      已跳过（--skip-ffmpeg）');
    return;
  }

  const asset = `ffmpeg-v${ELECTRON_VERSION}-${process.platform}-${process.arch}.zip`;

  // ELECTRON_MIRROR 走 electron 社区的既有约定（npmmirror 等镜像都用它）。
  // 国内直连 github.com 的 release 下载常年不稳，实测 curl 会 21 秒超时。
  // 镜像地址形如 https://registry.npmmirror.com/-/binary/electron，下面拼 /v<版本>/<文件名>。
  const mirror = (process.env.ELECTRON_MIRROR || '').trim().replace(/\/+$/, '');
  const url = mirror
    ? `${mirror}/v${ELECTRON_VERSION}/${asset}`
    : `https://github.com/electron/electron/releases/download/v${ELECTRON_VERSION}/${asset}`;
  log(`      ${url}`);

  let zip;
  try {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
    zip = Buffer.from(await res.arrayBuffer());
  } catch (e) {
    fail(
      `下载失败：${e.message}\n` +
      '  若本机走了 HTTP 代理，Node 的 fetch 不读系统代理设置，需要设 HTTPS_PROXY 环境变量。\n' +
      `  也可以手动下载后解出 ${inner} 放到 ${FFMPEG_DIR}/ 下，再加 --skip-ffmpeg 重跑：\n` +
      `  ${url}`
    );
  }

  // 不按固定文件名找 —— Electron 偶尔会把 dylib 放进子目录。按后缀匹配更稳。
  const entries = unzipList(zip);
  const hit = entries.find((e) => new RegExp(`(^|/)${inner.replace('.', '\\.')}$`).test(e.name));
  if (!hit) {
    fail(
      `zip 里找不到 ${inner}。实际条目：\n  ${entries.map((e) => e.name).join('\n  ')}\n` +
      '  Electron 的发布结构可能变了，需要更新本脚本的匹配规则。'
    );
  }

  rmrf(FFMPEG_DIR);
  fs.mkdirSync(FFMPEG_DIR, { recursive: true });
  const data = unzipEntry(zip, hit);
  fs.writeFileSync(path.join(FFMPEG_DIR, inner), data);

  log(`      ${inner}: ${mb(data.length)}`);
}

/* ───────────────────────── 步骤 4：Chromium ───────────────────────── */

/**
 * 从 fat jar 里取出 Playwright 三件套，返回它们的真实文件路径。
 *
 * 为什么不用 Maven 的 dependency:build-classpath：
 *   本仓库是多模块 reactor，单独 -pl mini-agent-tools 会因为没 install 过
 *   mini-agent-common 而解析失败；加 -am 又会让 outputFile 被前序模块的产物覆盖。
 *   而 fat jar 是步骤 1 的必然产物，BOOT-INF/lib/ 下就躺着这三个 jar 的原始文件，
 *   直接取出来用 —— 零解析、零版本号硬编码。
 *
 * 三者缺一不可：com.microsoft.playwright.CLI 在 playwright-<v>.jar 里，它靠
 * driver-<v>.jar 启动 node，driver 再从 driver-bundle-<v>.jar 解出真正的 node 二进制
 * （driver/win32_x64/node.exe 等，五平台各一份，这是 fat jar 里的第二大头，189MB）。
 */
async function playwrightJars() {
  const tmp = path.join(DESKTOP_DIR, '.build-tmp');
  rmrf(tmp);
  fs.mkdirSync(tmp, { recursive: true });

  const entries = listZipEntriesInFile(JAR_PATH)
    .filter((e) => /^BOOT-INF\/lib\/(playwright|driver|driver-bundle)-\d[^/]*\.jar$/.test(e.name));

  const need = ['playwright-', 'driver-', 'driver-bundle-'];
  const missing = need.filter((n) => !entries.some((e) => path.basename(e.name).startsWith(n)));
  if (missing.length) {
    rmrf(tmp);
    fail(
      `fat jar 里缺少 Playwright 组件：${missing.join(', ')}\n` +
      '  这三个 jar 缺任何一个，CLI 都起不来。检查 mini-agent-tools/pom.xml 的 playwright 依赖。'
    );
  }

  const jars = [];
  for (const e of entries) {
    const out = path.join(tmp, path.basename(e.name));
    await extractZipEntryToFile(JAR_PATH, e, out);
    jars.push(out);
    log(`      解出 ${path.basename(e.name)}（${mb(fs.statSync(out).size)}）`);
  }
  return { jars, tmp };
}

async function stepBrowsers(skip, jdk, withHeaded) {
  step(4, '下载 Chromium（随包分发）');
  if (skip) {
    if (!fs.existsSync(BROWSERS_DIR)) fail(`--skip-browsers 但 ${BROWSERS_DIR} 不存在`);
    log(`      已跳过（--skip-browsers），现有体积 ${mb(dirSize(BROWSERS_DIR))}`);
    return;
  }

  const { jars, tmp } = await playwrightJars();
  log(`      classpath: ${jars.map((j) => path.basename(j)).join(', ')}`);

  try {
    // 下到随包目录而不是默认缓存目录。CLI 与运行时后端都靠同一个环境变量定位，
    // 两边必须指向同一个路径 —— 不一致的后果是"明明装了却报 Executable doesn't exist"。
    const env = {
      ...process.env,
      PLAYWRIGHT_BROWSERS_PATH: BROWSERS_DIR,
      PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT: '600000'
    };

    // 先清空：Playwright 的版本化目录（chromium-<rev> / chromium_headless_shell-<rev>）
    // 升级后会换名字，旧目录不会自动删，留在包里只是白占体积。
    rmrf(BROWSERS_DIR);
    fs.mkdirSync(BROWSERS_DIR, { recursive: true });

    // --only-shell = 装 chromium 时跳过完整包，只下 headless shell。
    // 依据见文件头注释：headless=true 时 Playwright 解析到的是 chromium-headless-shell。
    // 注意这条路仍会顺带下 ffmpeg-<rev>（约 3.4MB）—— playwright 的 CLI 里
    // 只要安装目标属于 chromium 系列就会捎带 ffmpeg，用于录像，留着不影响。
    const installArgs = ['install', 'chromium'];
    if (!withHeaded) installArgs.push('--only-shell');

    const sep = IS_WIN ? ';' : ':';
    run(jdk.java, ['-cp', jars.join(sep), 'com.microsoft.playwright.CLI', ...installArgs], { env });

    log(`      Chromium: ${mb(dirSize(BROWSERS_DIR))}${withHeaded ? '（含完整包，有头调试可用）' : '（仅 headless shell）'}`);
    log(`      目录：${BROWSERS_DIR}`);
  } finally {
    // driver-bundle 解出来是 189MB，只是下载 Chromium 的临时工具，不该留在工作区
    rmrf(tmp);
  }
}

/* ─────────────── 步骤 5：内联 embedding 模型 ─────────────── */

/**
 * 把内联 embedding 的模型与 tokenizer 复制进随包目录。
 *
 * 为什么随包而不是首启下载：出厂形态的客户机可能完全离线，而首启下载失败时的
 * 表现是「应用起来了、只是语义检索不准」—— 用户和客服都很难把它跟「安装包不完整」
 * 联系起来。Chromium 那边踩过同一个坑，这里按同样方式处理。
 *
 * 为什么按字节数卡：模型被截断 / 只下了一半是**静默**故障。ORT 建会话失败后
 * {@code LocalOnnxEmbeddingModel} 会降级成「没有向量的检索」，应用照常启动。
 * 在构建期拦住，比在客户机上排查「为什么检索不准」便宜得多。
 *
 * tokenizer 的取值顺序是「先看 srcDir，再退到 HF 模型目录」：出厂路径下
 * CI 解开物料归档，模型与 tokenizer 就在同一个目录里，两者天然成对；
 * 本机开发时才需要退到 HF 目录去取。这个顺序不能反 —— 反了就没法用
 * 「一个目录」表达一份完整物料，CI 也就没法只靠下载归档完成备料。
 *
 * 校验规则本身在 scripts/lib/model-manifest.mjs，与 pack-models.mjs 共用同一份。
 */
function stepModels(skip, variant, srcDir) {
  step(5, `复制内联 embedding 模型（${variant}）`);

  if (skip) {
    if (!fs.existsSync(MODELS_DIR)) fail(`--skip-models 但 ${MODELS_DIR} 不存在`);
    log(`      已跳过（--skip-models），现有 ${fs.readdirSync(MODELS_DIR).length} 项 `
      + `${mb(dirSize(MODELS_DIR))}`);
    return;
  }

  // 先把源全部校验通过，再动目标目录。
  // 反过来会出现「旧物料被删了、新物料又没复制成」，下一次带 --skip-models
  // 的构建也跟着一起失败。
  let plan;
  let tokenizerFrom;
  try {
    ({ plan, tokenizerFrom } = resolveModelSources({
      variant,
      srcDir,
      fallbackTokenizerSrc: TOKENIZER_SRC
    }));
  } catch (e) {
    if (e instanceof ModelSourceError) fail(e.message);
    throw e;
  }

  // 必须显式打出 tokenizer 的来源。走 fallback 意味着物料目录里没有 tokenizer.json：
  // 本机因为有 HF 目录所以能过，而 CI 只解开归档、没有 HF 目录 —— 那边就会失败。
  // 也就是说不打出这一行，「物料归档漏了 tokenizer」在本机是看不出来的。
  log(tokenizerFrom === 'bundle'
    ? '      tokenizer 来源：物料目录自带'
    : `      tokenizer 来源：HF 模型目录（兜底）${TOKENIZER_SRC}`);

  rmrf(MODELS_DIR);
  fs.mkdirSync(MODELS_DIR, { recursive: true });
  for (const p of plan) {
    fs.copyFileSync(p.from, path.join(MODELS_DIR, p.name));
    log(`      ${p.name}  ${mb(p.bytes)}`);
  }
  log(`      合计 ${mb(dirSize(MODELS_DIR))}`);
  log(`      目录：${MODELS_DIR}`);
}

/* ───────────────────────── 步骤 6：electron-builder ───────────────────────── */

/**
 * 各目标平台允许的架构。
 *
 * <p>macOS 只允许 arm64 是**工程决策**，不是当前环境的限制 —— 内联 embedding 依赖的
 * {@code ai.djl.huggingface:tokenizers 0.36.0} 只带 {@code osx-aarch64} 的原生库，
 * 没有 {@code osx-x86_64}。在 Intel Mac 上它会静默降级：应用能启动，但语义检索类功能不工作。
 * 也就是说本工程打不出合法的 Intel 包，与其发一个坏包，不如在这里直接失败。
 */
const ALLOWED_ARCH = { mac: ['arm64'], win: ['x64'], linux: ['x64', 'arm64'] };

/** 把各种架构写法归一化到 Node 的 process.arch 口径（arm64 / x64 / ia32 / armv7l）。 */
function normalizeArch(a) {
  const s = String(a).trim().toLowerCase();
  if (s === 'aarch64' || s === 'arm64') return 'arm64';
  if (s === 'amd64' || s === 'x86_64' || s === 'x64') return 'x64';
  if (s === 'i386' || s === 'i686' || s === 'ia32') return 'ia32';
  if (s === 'armv7l' || s === 'armhf') return 'armv7l';
  return s;
}

/**
 * 校验「目标平台 × 当前平台 × 当前架构 × 调用方声明的期望架构」四者自洽。
 *
 * <p><b>为什么目标平台必须等于当前平台。</b>第 2 步的 jlink 按当前机器裁 JRE，
 * 而产出的 JRE 是平台专有的 —— Windows 版里那堆 `api-ms-win-core-*.dll` 拿到 macOS 上
 * 就是一堆废文件。所以在 A 平台构建 B 平台的包，必然塞进去一份在 B 上跑不起来的 JRE；
 * 而 electron-builder 未必会因此失败（Windows 上打 Linux 包有时能走完），
 * 于是产出一个「装得上、起不来」的包。与其这样，不如在这里直接拒绝。
 * 四个平台各跑一次，由 CI 矩阵（`.github/workflows/desktop-release.yml`）负责。
 *
 * <p><b>为什么还要架构断言。</b>jlink 与 electron-builder 都只按当前机器出包，
 * 不会去核对你「以为」自己在打什么架构。而 CI 矩阵里的 artifact 名
 * （`MiniAgent-mac-arm64` 之类）是手写字符串，与 runner 的实际架构没有任何绑定关系；
 * runner 标签本身也不与架构绑死 —— GitHub 的 macOS 标签里 `macos-latest` / `macos-14` /
 * `macos-15` 是 arm64，而 `macos-15-intel` / `macos-14-large` 才是 Intel。
 * 于是写错标签时整条链路会**照常成功**：JRE、安装包、`artifactName` 里的 `${arch}`
 * 全都按当前机器算，唯独上传时用的还是那个手写的名字 —— 名实不符，且没有任何一步报错。
 *
 * <p>对本工程尤其致命：macOS Intel 本来就不支持（见 {@link ALLOWED_ARCH}）。
 *
 * <p>不传 `--expect-arch` 时只校验前三条，本地构建不受影响。
 */
function assertHostMatches(targets, expectArch) {
  const names = Object.keys(ALLOWED_ARCH);
  for (const t of targets) {
    if (!ALLOWED_ARCH[t]) fail(`未知目标平台：${t}（支持 ${names.join(' / ')}）`);
  }

  const hostTarget = IS_WIN ? 'win' : IS_MAC ? 'mac' : 'linux';
  const foreign = targets.filter((t) => t !== hostTarget);
  if (foreign.length) {
    fail(
      `目标平台与当前平台不一致：请求 ${targets.join(',')}，当前平台是 ${hostTarget}。\n` +
      '  第 2 步的 jlink 按当前机器裁 JRE，而 JRE 是平台专有的 ——\n' +
      '  在 A 平台构建 B 平台的包，塞进去的是一份在 B 上跑不起来的 JRE。\n' +
      `  四个平台各跑一次（CI 矩阵负责），本机请用 --target=${hostTarget}。`
    );
  }

  for (const t of targets) {
    const allowed = ALLOWED_ARCH[t];
    if (!allowed.includes(process.arch)) {
      fail(
        `目标 ${t} 不支持当前架构 ${process.arch}。\n` +
        `  ${t} 允许的架构：${allowed.join(' / ')}\n` +
        (t === 'mac'
          ? '  macOS Intel（x64）不受支持：DJL tokenizers 没有 osx-x86_64 原生库，\n'
            + '  内联 embedding 会静默降级。请在 Apple Silicon（M 系）机器上构建。'
          : `  请在 ${allowed.join(' 或 ')} 架构的机器上构建。`)
      );
    }
  }

  if (expectArch) {
    const want = normalizeArch(expectArch);
    if (want !== process.arch) {
      fail(
        `架构不符：调用方声明 --expect-arch=${expectArch}（归一化为 ${want}），` +
        `但当前机器是 ${process.arch}。\n` +
        '  通常说明跑在了错误的 runner / 机器上。继续构建会打出一份名实不符的包，故直接终止。'
      );
    }
    log(`  目标断言：${hostTarget} / ${process.arch} ✓（--expect-arch=${expectArch}）`);
  }
}

/** 当前平台对应的 electron-builder 目标。 */
function defaultTargets() {
  if (IS_WIN) return ['win'];
  if (IS_MAC) return ['mac'];
  return ['linux'];
}

/**
 * 回读产物文件名，校验里面的架构标记与当前机器一致。
 *
 * <p>产物的平台与架构只体现在**名字**上（`package.json` 的 artifactName 模板
 * `${productName}-${version}-${os}-${arch}.${ext}`），CI 也靠它区分四个平台。
 * 所以构建完必须回读一遍，把「文件名说 arm64、实际是 x64」这类错配挡在本地。
 */
function verifyArtifactArch(files) {
  const re = /-(x64|arm64|ia32|armv7l)\./;
  let matched = 0;
  for (const f of files) {
    const m = f.match(re);
    if (!m) continue;
    matched++;
    if (normalizeArch(m[1]) !== process.arch) {
      fail(`产物 ${f} 文件名里的架构是 ${m[1]}，但当前机器是 ${process.arch} —— 名实不符。`);
    }
  }
  if (files.length && matched === 0) {
    log('      ⚠ 产物名里没有架构标记：确认 package.json 的 artifactName 仍含 ${arch}。' +
        '\n        丢了这个变量，CI 上传时就分不出同一平台的 x64 / arm64 产物。');
  }
}

function stepPackage(skip, targets) {
  step(6, '打包安装包');
  if (skip) {
    log('      已跳过（--skip-package）');
    return;
  }

  // 「目标平台 == 当前平台」「架构符合预期」这两件事已在 main() 的
  // assertHostMatches() 里统一校验过，这里不再重复，避免两处逻辑漂移。

  const bin = path.join(DESKTOP_DIR, 'node_modules', '.bin', IS_WIN ? 'electron-builder.cmd' : 'electron-builder');
  if (!fs.existsSync(bin)) {
    log('      node_modules 缺失，先安装依赖 ...');
    run(IS_WIN ? 'npm.cmd' : 'npm', ['install'], { cwd: DESKTOP_DIR });
  }
  if (!IS_WIN) {
    try { fs.chmodSync(bin, 0o755); } catch { /* ignore */ }
  }

  // 随包 JRE、Chromium、模型都必须在位 —— package.json 的 extraResources 直接引用它们，
  // 缺失时 electron-builder 只在日志里给一行 warning，打出来的包里点开才发现起不来。
  if (!fs.existsSync(JRE_DIR)) fail(`随包 JRE 不存在：${JRE_DIR}（先跑一次不带 --skip-jre 的构建）`);
  if (!fs.existsSync(BROWSERS_DIR)) fail(`随包 Chromium 不存在：${BROWSERS_DIR}（先跑一次不带 --skip-browsers 的构建）`);
  if (!fs.existsSync(MODELS_DIR)) fail(`随包模型不存在：${MODELS_DIR}（先跑一次不带 --skip-models 的构建）`);
  // --skip-models 只保证目录在，不保证内容齐 —— 这里补一次"成对"检查。
  // 缺 tokenizer 与缺模型的症状完全一样：应用照常启动，语义检索静默降级。
  if (!fs.existsSync(path.join(MODELS_DIR, 'tokenizer.json'))) {
    fail(`随包模型目录里没有 tokenizer.json：${MODELS_DIR}（模型与 tokenizer 必须成对）`);
  }

  const args = targets.map((t) => `--${t}`);
  run(bin, args, { cwd: DESKTOP_DIR });

  const distDir = path.join(DESKTOP_DIR, 'dist');
  if (fs.existsSync(distDir)) {
    const files = fs.readdirSync(distDir)
      .filter((f) => /\.(exe|dmg|AppImage|deb|zip)$/.test(f));
    verifyArtifactArch(files);
    if (files.length) {
      log('\n      产物：\n' + files
        .map((f) => `      ${f}  ${mb(fs.statSync(path.join(distDir, f)).size)}`)
        .join('\n'));
    }
  }
}

/* ───────────────────────── 主流程 ───────────────────────── */

function parseArgs(argv) {
  const opts = {
    skipJar: false, skipJre: false, skipFfmpeg: false, skipBrowsers: false,
    skipModels: false, skipPackage: false, withHeaded: false,
    modelVariant: 'int8', modelSrc: MODEL_SRC_DEFAULT,
    expectArch: null, targets: null
  };
  for (const a of argv) {
    if (a === '--skip-jar') opts.skipJar = true;
    else if (a === '--skip-jre') opts.skipJre = true;
    else if (a === '--skip-ffmpeg') opts.skipFfmpeg = true;
    else if (a === '--skip-browsers') opts.skipBrowsers = true;
    else if (a === '--skip-models') opts.skipModels = true;
    else if (a === '--skip-package') opts.skipPackage = true;
    else if (a === '--with-headed-chromium') opts.withHeaded = true;
    else if (a.startsWith('--model-variant=')) {
      opts.modelVariant = a.slice('--model-variant='.length).trim();
      if (!opts.modelVariant) fail('--model-variant= 后面要写变体名，例如 --model-variant=int8');
    } else if (a.startsWith('--model-src=')) {
      const v = a.slice('--model-src='.length).trim();
      if (!v) fail('--model-src= 后面要写目录路径');
      // 相对路径按调用时的 cwd 解析 —— 构建脚本本身的 cwd 是仓库根，
      // 但用户可能在别处调用，直接拼会得到意料之外的目录。
      opts.modelSrc = path.resolve(v);
    } else if (a.startsWith('--expect-arch=')) {
      opts.expectArch = a.slice('--expect-arch='.length).trim();
      if (!opts.expectArch) fail('--expect-arch= 后面要写架构，例如 --expect-arch=arm64');
    } else if (a.startsWith('--target=')) {
      opts.targets = a.slice('--target='.length).split(',').map((s) => s.trim()).filter(Boolean);
    } else if (a === '--help' || a === '-h') {
      console.log(HELP);
      process.exit(0);
    } else {
      fail(`未知参数：${a}（用 --help 查看用法）`);
    }
  }
  return opts;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const targets = opts.targets || defaultTargets();

  log('MiniAgent Desktop 构建');
  log(`  平台：${process.platform} / ${process.arch}`);
  log(`  目标：${targets.join(', ')}`);
  log(`  模型：${opts.modelVariant}`);
  log(`  仓库：${REPO_ROOT}`);

  // 放在最前面：跑错了机器就该立刻停，不要让 Maven 打包和上百 MB 的
  // Chromium 下载先跑完再失败。
  assertHostMatches(targets, opts.expectArch);

  // JDK 只在 jlink / Chromium 这两步用得上 —— 都 --skip 时不该因为这台机器
  // 没装 JDK 就让整个构建失败（比如只想复用已有物料、单纯重新出包）。
  let jdk = null;
  if (!opts.skipJre || !opts.skipBrowsers) {
    jdk = resolveJdk();
  }

  stepJar(opts.skipJar);
  stepJre(opts.skipJre);
  await stepFfmpeg(opts.skipFfmpeg);
  await stepBrowsers(opts.skipBrowsers, jdk, opts.withHeaded);
  stepModels(opts.skipModels, opts.modelVariant, opts.modelSrc);
  stepPackage(opts.skipPackage, targets);

  log('\n完成。产物在 mini-agent-desktop/dist/\n');
}

main().catch((e) => {
  console.error(`\n构建异常终止：${e && e.stack ? e.stack : e}\n`);
  process.exit(1);
});
