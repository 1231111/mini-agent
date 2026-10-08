/**
 * 内联 embedding 物料的唯一清单。
 *
 * 为什么单独抽一个模块：
 *   这批物料有**两个**使用者 —— 构建脚本（把物料放进出厂包）与打包脚本
 *   （把物料打成可发布的归档）。两者的校验规则必须完全一致：如果各写一份，
 *   改字节数时漏改一处，结果就是「打包时放行、构建时拦住」这种自相矛盾的状态，
 *   而它能一直潜伏到有人重新导出模型才爆。
 *
 * 所以这里只有一处定义：谁要说某个文件是多少字节，都必须来这儿读。
 *
 * 字节数不是"参考值"而是校验值：模型是典型的「缺了不报错、只降级」的物料 ——
 * 文件被截断或只下了一半时，ORT 建会话失败，语义检索静默关掉，而应用照常启动。
 * 在构建期卡一次，比在客户机上查"为什么检索不准"便宜得多。
 *
 * 数字来源：.verify/onnx/manifest.json（导出脚本自己写的）。
 */

import fs from 'node:fs';
import path from 'node:path';

/** 物料清单本身出错（文件缺失/字节数不符）时抛这个，由调用方决定怎么展示。 */
export class ModelSourceError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ModelSourceError';
  }
}

/**
 * 随包的内联 embedding 模型变体。
 *
 * 注意 fp32 的文件名里**没有** .fp32 —— 它是导出基准档，就叫 xxx.onnx。
 * 这个不对称是 manifest.json 里定的，别按变体名去推文件名。
 */
export const MODEL_VARIANTS = {
  int8: {
    file: 'yuan-embedding-2.0-zh.int8.onnx',
    bytes: 329744186,
    companion: null
  },
  fp16: {
    file: 'yuan-embedding-2.0-zh.fp16.onnx',
    bytes: 2749850,
    companion: { file: 'yuan-embedding-2.0-zh.fp16.onnx.data', bytes: 652627456 }
  },
  fp32: {
    file: 'yuan-embedding-2.0-zh.onnx',
    bytes: 2742236,
    companion: { file: 'yuan-embedding-2.0-zh.onnx.data', bytes: 1305247744 }
  }
};

/** tokenizer 文件名。物料归档与出厂包里的名字都必须与 Java 侧配置一致。 */
export const TOKENIZER_FILE = 'tokenizer.json';

/**
 * tokenizer.json 的字节数（439,377 B）。
 *
 * 它是 439KB 的小文件，但**必须校验**：tokenizer 与模型必须成对，版本错配时
 * token ids 与参考实现不一致 —— 向量照样算得出来，只是已经是错的，
 * 没有任何运行期异常可以提示你。
 */
export const TOKENIZER_BYTES = 439377;

/** ONNX 变体的默认来源目录（export_onnx.py 的产出位置，体积太大不入版本控制）。 */
export function defaultModelSrc(repoRoot) {
  return path.join(repoRoot, '.verify', 'onnx');
}

/**
 * tokenizer.json 的兜底来源：HF 原始模型目录。
 *
 * 它跟 HF 模型放在一起，而那个目录同样不入版本控制，所以本机没下过模型时
 * 会找不到。但**只要物料目录里自带 tokenizer.json，就轮不到这个兜底** ——
 * 这是物料自洽的关键：归档里同时有模型与 tokenizer，版本不可能错配。
 */
export function defaultTokenizerSrc(repoRoot) {
  return path.join(
    repoRoot, 'embedding-server', 'models', 'models',
    'IEITYuan--Yuan-embedding-2.0-zh', 'snapshots', 'master', TOKENIZER_FILE
  );
}

/** 人类可读的变体名列表，用于错误提示。 */
export const VARIANT_NAMES = Object.keys(MODEL_VARIANTS);

/**
 * 解析出「要复制/打包哪些文件」，并在过程中完成全部校验。
 *
 * 调用方要先拿到完整的 plan 再动手，不能边校验边复制 —— 否则会出现
 * 「目标目录里的旧物料被删了、新物料又没复制成」的中间态，
 * 之后连 `--skip-models` 的构建都会跟着失败。
 *
 * tokenizer 的取值顺序：
 *   1. `<srcDir>/tokenizer.json`（物料归档自带 —— 出厂路径走这条）
 *   2. `fallbackTokenizerSrc`（本机开发路径，从 HF 模型目录取）
 * 两条都落空才报错。顺序不能反：反了就无法用「一个目录」表达一份完整物料，
 * CI 上也就没法只靠下载归档完成备料。
 *
 * @param {object} o
 * @param {string} o.variant            变体名，必须在 MODEL_VARIANTS 里
 * @param {string} o.srcDir             ONNX 变体所在目录
 * @param {string} o.fallbackTokenizerSrc tokenizer 的兜底来源
 * @returns {{plan: Array<{from: string, name: string, bytes: number, role: string}>, tokenizerFrom: 'bundle'|'fallback'}}
 * @throws {ModelSourceError}
 */
export function resolveModelSources({ variant, srcDir, fallbackTokenizerSrc }) {
  const spec = MODEL_VARIANTS[variant];
  if (!spec) {
    throw new ModelSourceError(
      `未知的模型变体：${variant}\n` +
      `  可选：${VARIANT_NAMES.join(' / ')}\n` +
      '  int8 体积最小且最快，真实改写检索的排序与 fp32 逐项相同\n' +
      '  （依据见 .verify/onnx/rank-fidelity.json）。'
    );
  }

  if (!fs.existsSync(srcDir)) {
    throw new ModelSourceError(
      `模型源目录不存在：${srcDir}\n` +
      '  这个目录由 .verify/onnx/export_onnx.py 产出，体积太大所以不入版本控制。\n' +
      '  先在本机导出一次（--variants 是开关，不带值，会同时导 fp16 与 int8）：\n' +
      '    python .verify/onnx/export_onnx.py --variants\n' +
      '  或用 --model-src=<dir> 指向已有的制品目录（CI 上就是下载后解开的物料归档）。'
    );
  }

  const plan = [];

  for (const part of [spec, spec.companion].filter(Boolean)) {
    const from = path.join(srcDir, part.file);
    if (!fs.existsSync(from)) {
      throw new ModelSourceError(
        `缺少模型文件：${from}\n` +
        (part === spec.companion
          ? `  ${variant} 变体的权重是外置的，${part.file} 必须与主文件同目录。\n` +
            '  只复制主文件不够 —— 图里存的是相对路径，权重缺失会在建会话时失败。\n'
          : '') +
        '  重新导出：python .verify/onnx/export_onnx.py --variants'
      );
    }
    const actual = fs.statSync(from).size;
    if (actual !== part.bytes) {
      throw new ModelSourceError(
        `模型文件字节数不符：${part.file}\n` +
        `  期望 ${part.bytes.toLocaleString('en-US')} B，实际 ${actual.toLocaleString('en-US')} B\n` +
        '  常见原因是复制/下载中断，或导出参数变了导致新图与 manifest.json 记录的不一致。\n' +
        '  截断的模型不会让构建失败，但会让出厂包里的语义检索静默失效 —— 所以这里直接停。\n' +
        '  若确实是重新导出的新版本，请同步更新 scripts/lib/model-manifest.mjs 里的字节数。'
      );
    }
    plan.push({
      from,
      name: part.file,
      bytes: actual,
      role: part === spec.companion ? 'onnx-weights' : 'onnx-model'
    });
  }

  // tokenizer 必须与模型成对：只有模型没有 tokenizer 时结果一样是降级 ——
  // LocalOnnxEmbeddingModel 的可用性判断里这两个条件是并列的。
  const bundledTokenizer = path.join(srcDir, TOKENIZER_FILE);
  const tokenizerFrom = fs.existsSync(bundledTokenizer) ? 'bundle' : 'fallback';
  const tokenizerPath = tokenizerFrom === 'bundle' ? bundledTokenizer : fallbackTokenizerSrc;

  if (!fs.existsSync(tokenizerPath)) {
    throw new ModelSourceError(
      `找不到 tokenizer.json\n` +
      `  物料目录里没有：${bundledTokenizer}\n` +
      `  兜底位置也没有：${fallbackTokenizerSrc}\n` +
      '  它随 HF 原始模型一起下载，而那个目录不入版本控制。\n' +
      `  目标路径：${path.dirname(fallbackTokenizerSrc)}\n` +
      '  仓库里的 embedding-server/fetch_modelscope.sh 是容器内脚本，它的\n' +
      '  cache_dir=/models 对应宿主机就是这个目录 —— 在容器里跑它即可落到位。\n' +
      '  注意 entrypoint.sh 走的是 HF 缓存（embedding-server/models/hf），不是这里。'
    );
  }

  const tkBytes = fs.statSync(tokenizerPath).size;
  if (tkBytes !== TOKENIZER_BYTES) {
    throw new ModelSourceError(
      `tokenizer.json 字节数不符：期望 ${TOKENIZER_BYTES} B，实际 ${tkBytes} B\n` +
      '  换了模型版本就会这样。tokenizer 与模型必须成对 —— 版本错配时\n' +
      '  token ids 与参考实现不一致，向量照样算得出来，但已经是错的。'
    );
  }
  plan.push({ from: tokenizerPath, name: TOKENIZER_FILE, bytes: tkBytes, role: 'tokenizer' });

  return { plan, tokenizerFrom };
}

/**
 * 算出目录里 plan 各项的合计字节数。
 *
 * 注意这是**解压后**的体积，不是传输体积 —— 归档走 gzip 之后会小一些，
 * 但那不是出厂包里的占用，别混着看。
 */
export function planBytes(plan) {
  return plan.reduce((sum, p) => sum + p.bytes, 0);
}

/** MiB 口径的人类可读体积（与文档里 1.32 GB 那种算法一致）。 */
export const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
