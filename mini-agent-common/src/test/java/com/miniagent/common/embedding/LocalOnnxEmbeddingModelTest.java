package com.miniagent.common.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 内联 ONNX embedding 对 Python 参考实现的一致性闸门。
 * <p>
 * 门槛与 <code>.verify/onnx/check_consistency.py</code> 一致：cosine &gt;= 0.9999。
 * 这个数字不是拍脑袋定的 —— 它是「换实现但向量检索结果不变」的实用界线：
 * 再低就会开始影响召回排序，而余弦相似度的排序对末几位小数并不敏感。
 * <p>
 * 模型文件（655MB）与金标准都放在仓库根的 <code>.verify/</code> 下，不进
 * <code>src/test/resources</code>；没有这些文件的环境直接跳过，不让构建挂掉。
 * 生成方式见 <code>.verify/RESULT-onnx-embedding.md</code>。
 */
class LocalOnnxEmbeddingModelTest {

    private static final double THRESHOLD = 0.9999;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path root() {
        Path here = Paths.get("").toAbsolutePath();
        // surefire 的工作目录是模块目录（mini-agent-*），仓库根在它上一级
        String name = here.getFileName() == null ? "" : here.getFileName().toString();
        return name.startsWith("mini-agent-") ? here.getParent() : here;
    }

    private static Path model() {
        return root().resolve(".verify/onnx/yuan-embedding-2.0-zh.fp16.onnx");
    }

    private static Path tokenizer() {
        return root().resolve("embedding-server/models/models/"
                + "IEITYuan--Yuan-embedding-2.0-zh/snapshots/master/tokenizer.json");
    }

    private static Path golden() {
        return root().resolve(".verify/onnx/probe-golden.json");
    }

    private static void requireFixtures() {
        assumeTrue(Files.isRegularFile(model()), "缺 .verify/onnx/yuan-embedding-2.0-zh.fp16.onnx，跳过");
        assumeTrue(Files.isRegularFile(tokenizer()), "缺 tokenizer.json，跳过");
        assumeTrue(Files.isRegularFile(golden()), "缺 probe-golden.json，跳过");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 模型缺失时必须"不可用"而不是抛异常 —— 配置缺失应表现为功能降级，不是启动崩溃。 */
    @Test
    void missingModelDegradesInsteadOfThrowing() {
        Path bogus = root().resolve(".verify/onnx/__not_here__.onnx");
        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(bogus, bogus, 2)) {
            assertFalse(m.isAvailable());
            assertTrue(m.initError() != null && !m.initError().isBlank(),
                    "初始化失败必须留下可读的原因");
            assertEquals(0, m.embed("随便一句").length);
            assertTrue(m.embedAll(List.of("a", "b")).isEmpty());
        }
    }

    /** 拿不到向量时返回长度 0 的占位，保证条数与入参一致（否则调用方按下标取会整体错位）。 */
    @Test
    void embedAllAlwaysReturnsOneEntryPerInput() {
        requireFixtures();
        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(model(), tokenizer(), 4)) {
            assertTrue(m.isAvailable(), "模型应能加载: " + m.initError());
            List<String> in = List.of("第一条", "", "第三条", "第四条");
            List<float[]> out = m.embedAll(in);
            assertEquals(in.size(), out.size());
            out.forEach(v -> assertEquals(m.dimension(), v.length));
        }
    }

    /**
     * 两个入口对同一输入必须给同一个答案。
     * <p>
     * 这条是防回归的：初版 embed() 里带了 blank 判断，于是 embed("") 返回空数组，
     * 而 embedAll(List.of("")) 返回正常向量。
     */
    @Test
    void blankInputIsConsistentAcrossBothEntryPoints() {
        requireFixtures();
        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(model(), tokenizer(), 4)) {
            assertTrue(m.isAvailable(), "模型应能加载: " + m.initError());
            float[] single = m.embed("");
            List<float[]> batch = m.embedAll(List.of(""));
            assertEquals(1, batch.size());
            assertEquals(batch.get(0).length, single.length,
                    "embed(\"\") 与 embedAll([\"\"]) 的长度必须一致");
            assertTrue(single.length > 0, "空串是合法输入，应产出向量而不是空数组");
        }
    }

    /** 主闸门：与 Python 金标准逐条比 cosine。 */
    @Test
    void matchesPythonReference() throws Exception {
        requireFixtures();
        JsonNode samples = MAPPER.readTree(golden().toFile()).get("samples");
        List<String> texts = new ArrayList<>();
        List<float[]> refs = new ArrayList<>();
        for (JsonNode s : samples) {
            texts.add(s.get("text").asText());
            JsonNode ref = s.get("ref_norm");
            float[] v = new float[ref.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) ref.get(i).asDouble();
            }
            refs.add(v);
        }

        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(model(), tokenizer(), 8)) {
            assertTrue(m.isAvailable(), "模型应能加载: " + m.initError());
            assertEquals(refs.get(0).length, m.dimension(),
                    "维度必须与参考一致 —— 不一致说明加载的不是同一个模型");

            // 逐条与批量都要比：批量会按 token 数分桶后再补齐，
            // 分桶与手工 padding 写错只会体现在批量结果上。
            List<float[]> singles = new ArrayList<>();
            for (String t : texts) {
                singles.add(m.embed(t));
            }
            List<float[]> batches = m.embedAll(texts);

            double worstSingle = 1, worstBatch = 1, worstCross = 1;
            int worstIdx = -1;
            for (int i = 0; i < texts.size(); i++) {
                double cs = cosine(singles.get(i), refs.get(i));
                double cb = cosine(batches.get(i), refs.get(i));
                double cc = cosine(batches.get(i), singles.get(i));
                if (cs < worstSingle) {
                    worstSingle = cs;
                }
                if (cb < worstBatch) {
                    worstBatch = cb;
                    worstIdx = i;
                }
                worstCross = Math.min(worstCross, cc);
                assertTrue(singles.get(i).length > 0 && batches.get(i).length > 0,
                        "第 " + i + " 条拿不到向量（空白/空串也必须产出向量）");
            }

            String id = samples.get(worstIdx).get("id").asText();
            assertTrue(worstSingle >= THRESHOLD,
                    "逐条输入 cosine 未达标: " + worstSingle);
            assertTrue(worstBatch >= THRESHOLD,
                    "批量输入 cosine 未达标: " + worstBatch + "（最差样本 " + id + "）");
            assertTrue(worstCross >= THRESHOLD,
                    "同一文本「单独编码」与「批次内编码」结果不一致: " + worstCross
                            + " —— 查 attention_mask 与补齐位");
        }
    }

    /** 兜底：两批长短差很大时也不能错位（触发分桶边界）。 */
    @Test
    void mixedLengthBatchKeepsAlignment() {
        requireFixtures();
        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(model(), tokenizer(), 4)) {
            assertTrue(m.isAvailable(), "模型应能加载: " + m.initError());
            String longText = "上下文管理的核心问题是如何在有限的窗口里保留最有价值的信息。".repeat(60);
            List<String> in = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                in.add(i == 17 ? longText : "第 " + i + " 条短记忆");
            }
            List<float[]> out = m.embedAll(in);
            assertEquals(in.size(), out.size());
            // 长文本那条必须与单独编码一致 —— 它在自己那一桶里，若分桶下标回填写错，
            // 它会与旁边的短文本错位，而条数检查看不出来。
            double c = cosine(out.get(17), m.embed(longText));
            assertTrue(c >= THRESHOLD, "长文本在混合批次里与单独编码不一致: " + c);
        }
    }
}
