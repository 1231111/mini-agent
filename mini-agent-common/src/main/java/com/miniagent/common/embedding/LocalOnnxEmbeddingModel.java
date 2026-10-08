package com.miniagent.common.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内 ONNX embedding：用 onnxruntime 直接跑图，替掉 embedding-server 那个 Python sidecar。
 * <p>
 * <b>为什么池化 / Dense / 归一化不在这里做</b>：这三步都已经烘进 ONNX 图里了
 * （导出脚本 <code>.verify/onnx/export_onnx.py</code>，管道定义读自模型目录的
 * <code>modules.json</code>）。图里已经做过的事在 Java 里再做一遍，就等于有了第二个
 * 事实来源 —— 哪天两边不一致，会非常难查。所以这里只负责「tokenize → 喂图 → 取输出」。
 * <p>
 * <b>为什么不自己实现 tokenizer</b>：用的是 DJL 的 {@link HuggingFaceTokenizer}，
 * 它底层就是 HuggingFace 的 Rust tokenizers（与 Python 侧同一个库）。实测 20 条边界样本
 * （超长 / 空串 / 单空格 / emoji / 全角 / [UNK] 罕见字 / 大小写 / 换行）的 token ids
 * 与 Python 参考逐 token 一致，向量 cosine 1.0000000000。
 * <p>
 * <b>padding / truncation 不写代码</b>：{@code tokenizer.json} 里内建了
 * <code>padding(BatchLongest/Right)</code> 与 <code>truncation(LongestFirst/max_length=512)</code>，
 * DJL 会自动应用（实测显式传 optPadding/optTruncation/optMaxLength 与不传结果完全相同）。
 * <p>
 * 线程安全：<code>OrtSession.run</code> 与 DJL tokenizer 均无状态，可并发调用；
 * 初始化走双重检查锁。
 */
@Slf4j
public class LocalOnnxEmbeddingModel implements AutoCloseable {

    /**
     * 图里已做 L2 归一化的那个输出。取这个，Java 侧不要再归一化一遍。
     * 另一个输出是 {@code sentence_embedding}（未归一化），只在数值比对时用。
     */
    private static final String OUTPUT_NORMALIZED = "normalized_embedding";
    private static final String INPUT_IDS = "input_ids";
    private static final String INPUT_MASK = "attention_mask";

    /**
     * 单批最多多少条。不设上限时一次塞几千条会同时放大两个东西：
     * 一是 padding 后 <code>batch × seqLen</code> 的输入张量，二是 BERT 的 attention 矩阵
     * （batch × heads × seq² × 4B）。按批内最长 512 估算，16 条时 attention 约 200MB，
     * 再往上就不划算了。调用方给多长都行，这里自己切。
     */
    private static final int MAX_BATCH_SIZE = 16;

    /**
     * 同批内「最长 token 数 / 最短 token 数」的比例上限。
     * <p>
     * 这条直接决定性能：图里的 BatchLongest 式 padding 会把整批补到批内最长，
     * 长短混装时短文本白算的量随比例线性上升。实测 20 条（含一条被截断到 512 token 的）
     * 整批一次跑要 15627ms，逐条 3475ms —— 差 4.5 倍，全花在补齐位的空算上。
     */
    private static final double MAX_LENGTH_RATIO = 2.0;

    /**
     * 判定「这个 .onnx 是不是把权重放在外部」的阈值。
     * <p>
     * 导出的三档差三个数量级：int8 是单文件（329,744,186 B，权重烘进图内），
     * fp32 与 fp16 的主文件都只有 2.7 MB，权重全在同名的 {@code .onnx.data} 里。
     * 用一个下限就能稳定区分，而且不依赖文件名 —— 换变体时有人可能把它改成任意名字。
     */
    private static final long EXTERNAL_WEIGHTS_MAX_MAIN_BYTES = 10_000_000L;

    private final Path modelPath;
    private final Path tokenizerPath;
    private final int threads;

    private volatile OrtSession session;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile int dimension = -1;
    private volatile boolean initialized;
    /** 非 null 表示初始化失败过，后续调用直接降级，不再重复尝试。 */
    private volatile String initError;

    public LocalOnnxEmbeddingModel(Path modelPath, Path tokenizerPath, int threads) {
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.threads = threads > 0 ? threads : 4;
    }

    /** 模型与 tokenizer 文件都在且已成功建会话。 */
    public boolean isAvailable() {
        return ensureInitialized();
    }

    /** 输出向量维度（从图里读，不是配置里抄的）。未初始化时返回 -1。 */
    public int dimension() {
        return ensureInitialized() ? dimension : -1;
    }

    /** 初始化失败的原因，未失败时为 null。 */
    public String initError() {
        return initError;
    }

    /**
     * 批量嵌入。失败返回空列表（与 {@link SharedEmbeddingModel} 原有的降级语义一致：
     * 拿不到向量时调用方走词法匹配，而不是抛异常打断本轮对话）。
     * <p>
     * 返回列表长度<b>恒等于</b>入参长度：拿不到向量的位置用长度 0 的数组占位。
     * 调用方按下标取向量，长度对不上会整体错位，那种错比整批失败难查得多。
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        if (!ensureInitialized()) {
            return new ArrayList<>();
        }
        int n = texts.size();
        float[][] result = new float[n][];
        try {
            // 先逐条编码拿到真实 token 数（单条不 padding），再据此分批 —— 只 tokenize 一次。
            // 不把整批直接丢给 batchEncode 的原因见 MAX_LENGTH_RATIO。
            Encoding[] single = new Encoding[n];
            for (int i = 0; i < n; i++) {
                single[i] = tokenizer.encode(texts.get(i) == null ? "" : texts.get(i));
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingInt(i -> single[i].getIds().length));

            int from = 0;
            while (from < n) {
                int to = from + 1;
                int minLen = single[order[from]].getIds().length;
                while (to < n && to - from < MAX_BATCH_SIZE) {
                    int len = single[order[to]].getIds().length;
                    // 批内最长不超过最短的 MAX_LENGTH_RATIO 倍（+1 是避开 0 长度导致的除零式比较）
                    if (len > (minLen + 1) * MAX_LENGTH_RATIO) {
                        break;
                    }
                    to++;
                }
                try {
                    List<float[]> vecs = runPackedBatch(single, order, from, to);
                    for (int k = 0; k < vecs.size(); k++) {
                        result[order[from + k]] = vecs.get(k);
                    }
                } catch (Exception batchErr) {
                    log.warn("内联 embedding 单批失败（{} 条，跳过）: {}", to - from, batchErr.toString());
                }
                from = to;
            }
        } catch (Exception e) {
            log.warn("内联 embedding 失败: {}", e.toString());
        }
        List<float[]> out = new ArrayList<>(n);
        for (float[] v : result) {
            out.add(v == null ? new float[0] : v);
        }
        return out;
    }

    /**
     * 单条嵌入。失败返回长度 0 的数组。
     * <p>
     * 注意这里<b>不</b>对空串/空白做特判：空串在本模型里是合法输入，会编码成
     * {@code [CLS][SEP]} 两个 token 并产出正常向量（与参考实现一致）。
     * "空文本要不要算"是上层策略，由 {@link SharedEmbeddingModel#embed} 的
     * {@code isBlank} 判断决定 —— 在这一层再判一次，会让
     * {@code embed("")} 与 {@code embedAll(List.of(""))} 给出不同答案。
     */
    public float[] embed(String text) {
        List<float[]> r = embedAll(java.util.Collections.singletonList(text));
        return r.isEmpty() ? new float[0] : r.get(0);
    }

    /** 释放 ONNX 会话。{@code OrtEnvironment} 是全局单例，不能在这里关。 */
    @Override
    public void close() {
        OrtSession s = session;
        session = null;
        tokenizer = null;
        initialized = false;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                log.debug("关闭 ONNX 会话异常: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------

    private boolean ensureInitialized() {
        if (initialized) {
            return initError == null;
        }
        synchronized (this) {
            if (initialized) {
                return initError == null;
            }
            try {
                if (!Files.isRegularFile(modelPath)) {
                    fail("模型文件不存在: " + modelPath
                            + "（出厂包应指向安装目录 resources/models 下的 .onnx）");
                } else if (needsExternalWeights(modelPath)
                        && !Files.isRegularFile(externalWeightsOf(modelPath))) {
                    fail("模型的外置权重缺失: " + externalWeightsOf(modelPath)
                            + "（主文件只有 " + Files.size(modelPath) + " 字节，说明权重不在图里。"
                            + "fp32 / fp16 变体的权重放在同名的 .onnx.data 中，必须与 .onnx 同目录；"
                            + "int8 变体是单文件，不会走到这一条）");
                } else if (!Files.isRegularFile(tokenizerPath)) {
                    fail("tokenizer 文件不存在: " + tokenizerPath);
                } else {
                    OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                    opts.setIntraOpNumThreads(threads);
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    long t0 = System.currentTimeMillis();
                    session = OrtEnvironment.getEnvironment()
                            .createSession(modelPath.toString(), opts);
                    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, new HashMap<>());
                    dimension = readDimension(session);
                    log.info("内联 embedding 就绪: {} (dim={}, threads={}, 加载耗时 {} ms)",
                            modelPath.getFileName(), dimension, threads,
                            System.currentTimeMillis() - t0);
                }
            } catch (Throwable t) {
                fail("初始化异常: " + t);
            }
            initialized = true;
            return initError == null;
        }
    }

    /** 外置权重的约定路径：{@code <模型文件名>.data}，与模型同目录。 */
    private static Path externalWeightsOf(Path model) {
        return model.resolveSibling(model.getFileName().toString() + ".data");
    }

    /** 主文件小于阈值 → 权重不在图里，必须有外置 {@code .data} 才算完整。 */
    private static boolean needsExternalWeights(Path model) throws java.io.IOException {
        return Files.size(model) < EXTERNAL_WEIGHTS_MAX_MAIN_BYTES;
    }

    private void fail(String msg) {
        initError = msg;
        log.error("内联 embedding 不可用：{}。将回退为不带向量的检索", msg);
    }

    /** 从图的输出定义里读维度，而不是相信配置文件里写的数字。 */
    private static int readDimension(OrtSession session) throws Exception {
        NodeInfo info = session.getOutputInfo().get(OUTPUT_NORMALIZED);
        if (info == null) {
            throw new IllegalStateException("模型没有 " + OUTPUT_NORMALIZED + " 输出，实际输出="
                    + session.getOutputInfo().keySet()
                    + "（运行 .verify/onnx/export_onnx.py 重新导出）");
        }
        long[] shape = ((TensorInfo) info.getInfo()).getShape();
        return (int) shape[shape.length - 1];
    }

    /**
     * 把 [from, to) 这批手工右侧补齐后喂图。
     * <p>
     * 手工补齐而不是交给 {@code batchEncode}：批次的边界是按 token 数排过序后自己切的，
     * 交给库再补一次等于让它按它自己的规则重排，白白把已经控制好的 padding 又放大回去。
     */
    private List<float[]> runPackedBatch(Encoding[] single, Integer[] order, int from, int to)
            throws Exception {
        int rows = to - from;
        int cols = 0;
        for (int k = from; k < to; k++) {
            cols = Math.max(cols, single[order[k]].getIds().length);
        }
        long[] flatIds = new long[rows * cols];
        long[] flatMask = new long[rows * cols];
        for (int k = from; k < to; k++) {
            long[] ids = single[order[k]].getIds();
            long[] mask = single[order[k]].getAttentionMask();
            int off = (k - from) * cols;
            System.arraycopy(ids, 0, flatIds, off, ids.length);
            System.arraycopy(mask, 0, flatMask, off, mask.length);
            // 尾部留 0：[PAD] 的 id 就是 0，mask 也必须是 0，
            // 否则 mean pooling 的 sum_mask 会把补齐位算进分母
        }
        return runOnnx(flatIds, flatMask, rows, cols);
    }

    private List<float[]> runOnnx(long[] flatIds, long[] flatMask, int rows, int cols) throws Exception {
        long[] shape = {rows, cols};
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), LongBuffer.wrap(flatIds), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(
                     OrtEnvironment.getEnvironment(), LongBuffer.wrap(flatMask), shape);
             OrtSession.Result result = session.run(Map.of(
                     INPUT_IDS, idsTensor, INPUT_MASK, maskTensor))) {
            OnnxTensor out = (OnnxTensor) result.get(OUTPUT_NORMALIZED)
                    .orElseThrow(() -> new IllegalStateException("结果里没有 " + OUTPUT_NORMALIZED));
            int dim = (int) out.getInfo().getShape()[1];
            FloatBuffer buf = out.getFloatBuffer();
            List<float[]> res = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) {
                float[] v = new float[dim];
                buf.get(v);
                res.add(v);
            }
            return res;
        }
    }
}
