import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.BufferedWriter;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 侧内联 embedding 探针 —— 只做验证，不改项目代码。
 *
 * 要回答三个问题：
 *   Q1 DJL 的 HuggingFaceTokenizer 不显式传 padding/truncation 时，会不会自动应用
 *      tokenizer.json 里内建的 BatchLongest/max_length=512？还是必须显式 optPadding(true)？
 *   Q2 它产出的 token ids 与 Python 的 tokenizers 是否逐 token 一致？
 *   Q3 onnxruntime 1.20.0 的 Java 绑定跑出来的 normalized_embedding，与 Python 参考向量的
 *      cosine 是否 >= 0.9999？（顺带验证它能不能正确加载 external data 的 1.3GB 模型）
 *
 * 所以两种 tokenizer 配置各跑一遍，ids 与向量都写出来，交给 Python 侧判定。
 *
 * 用法：
 *   java Probe <model.onnx> <tokenizer.json> <probe-texts.txt> <probe-java.json>
 */
public class Probe {

    /** 与 Python 侧 check_consistency.py / bench.py 的 --threads 8 对齐，否则 GEMM 分块不同会引入噪声。 */
    private static final int THREADS = 8;

    /** 归一化输出：Java 侧实际要用的那个。 */
    private static final String NORM = "normalized_embedding";
    /** 未归一化输出：只用于比绝对误差 —— 归一化会把绝对差除小，单独比才能看出真实偏差。 */
    private static final String RAW = "sentence_embedding";

    public static void main(String[] args) throws Exception {
        Path onnx = Paths.get(args[0]).toAbsolutePath();
        Path tokJson = Paths.get(args[1]).toAbsolutePath();
        Path textsFile = Paths.get(args[2]);
        Path outFile = Paths.get(args[3]);

        List<String> sids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String line : Files.readAllLines(textsFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            int t = line.indexOf('\t');
            sids.add(line.substring(0, t));
            // 文本里含换行（s19），所以走 base64，避免自定义行格式被换行撕开
            texts.add(new String(Base64.getDecoder().decode(line.substring(t + 1)), StandardCharsets.UTF_8));
        }
        System.out.printf("样本 %d 条%n", texts.size());

        // ---------- Q1/Q2: 两种 tokenizer 配置 ----------
        HuggingFaceTokenizer tokPlain = HuggingFaceTokenizer.newInstance(tokJson, new HashMap<>());
        HuggingFaceTokenizer tokExplicit = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokJson)
                .optPadding(true)
                .optTruncation(true)
                .optMaxLength(512)
                .build();

        Encoding[] encPlain = tokPlain.batchEncode(texts);
        Encoding[] encExpl = tokExplicit.batchEncode(texts);
        System.out.println("plain    长度: " + lens(encPlain));
        System.out.println("explicit 长度: " + lens(encExpl));

        // ---------- Q3: ONNX ----------
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(THREADS);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        long t0 = System.currentTimeMillis();
        OrtSession sess = env.createSession(onnx.toString(), so);
        System.out.printf("ONNX 加载 %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
        System.out.println("  inputs : " + sess.getInputNames());
        System.out.println("  outputs: " + sess.getOutputNames());

        Map<String, float[][]> outPlain = infer(env, sess, encPlain);
        Map<String, float[][]> outExpl = infer(env, sess, encExpl);
        System.out.printf("推理完成，维度 %d%n", outPlain.get(NORM)[0].length);

        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("{\n\"plain\": ");
            w.write(toJson(sids, encPlain, outPlain));
            w.write(",\n\"explicit\": ");
            w.write(toJson(sids, encExpl, outExpl));
            w.write("\n}\n");
        }
        System.out.println("落盘 " + outFile);

        sess.close();
        so.close();
        env.close();
    }

    private static String lens(Encoding[] encs) {
        StringBuilder sb = new StringBuilder();
        for (Encoding e : encs) {
            sb.append(e.getIds().length).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * 批次推理。形状按批内最长对齐，短的在尾部补 pad_id=0、mask=0。
     * <p>
     * 即使 tokenizer 没做 padding 也能算出正确结果 —— attention_mask 会告诉模型哪些位置是填的。
     * <p>
     * 一次 run 同时取两个输出（归一化 + 未归一化），不用跑两遍。
     */
    private static Map<String, float[][]> infer(OrtEnvironment env, OrtSession sess, Encoding[] encs)
            throws Exception {
        int n = encs.length;
        int maxLen = 0;
        for (Encoding e : encs) {
            maxLen = Math.max(maxLen, e.getIds().length);
        }
        long[] flatIds = new long[n * maxLen];
        long[] flatMask = new long[n * maxLen];
        for (int i = 0; i < n; i++) {
            long[] ids = encs[i].getIds();
            long[] mask = encs[i].getAttentionMask();
            System.arraycopy(ids, 0, flatIds, i * maxLen, ids.length);
            System.arraycopy(mask, 0, flatMask, i * maxLen, mask.length);
            // 尾部默认 0，正好是 [PAD] 与 mask=0
        }
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(flatIds), new long[]{n, maxLen});
             OnnxTensor tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(flatMask), new long[]{n, maxLen});
             OrtSession.Result r = sess.run(Map.of("input_ids", tIds, "attention_mask", tMask))) {
            Map<String, float[][]> res = new HashMap<>();
            for (String name : new String[]{NORM, RAW}) {
                // 1.20.0 的 Result.get(String) 返回 Optional<OnnxValue>（与 langchain4j
                // 字节码里那个 Result.get(int) 是两套重载，别混）
                OnnxTensor out = (OnnxTensor) r.get(name)
                        .orElseThrow(() -> new IllegalStateException("没有 " + name + " 输出"));
                int dim = (int) out.getInfo().getShape()[1];
                float[][] mat = new float[n][dim];
                FloatBuffer fb = out.getFloatBuffer();
                for (int i = 0; i < n; i++) {
                    fb.get(mat[i]);
                }
                res.put(name, mat);
            }
            return res;
        }
    }

    private static String toJson(List<String> sids, Encoding[] encs, Map<String, float[][]> outs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sids.size(); i++) {
            sb.append(i == 0 ? "{" : ",{");
            sb.append("\"id\":\"").append(sids.get(i)).append("\",");
            long[] ids = encs[i].getIds();
            long[] mask = encs[i].getAttentionMask();
            sb.append("\"len\":").append(ids.length).append(",\"ids\":[");
            for (int j = 0; j < ids.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(ids[j]);
            }
            sb.append("],\"mask\":[");
            for (int j = 0; j < mask.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(mask[j]);
            }
            sb.append("],\"vec\":");
            appendVec(sb, outs.get(NORM)[i]);
            sb.append(",\"vecraw\":");
            appendVec(sb, outs.get(RAW)[i]);
            // 注意这里只加 "}"：数组的 "]" 已经由 appendVec 收掉了。
            // 写成 "]}" 会多出一个 "]" —— 手写 JSON 拼接最经典的坑。
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static void appendVec(StringBuilder sb, float[] v) {
        sb.append('[');
        for (int j = 0; j < v.length; j++) {
            if (j > 0) {
                sb.append(',');
            }
            sb.append(v[j]);
        }
        sb.append(']');
    }
}
