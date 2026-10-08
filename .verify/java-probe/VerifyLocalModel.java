import com.miniagent.common.embedding.LocalOnnxEmbeddingModel;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 直接验证生产类 {@link LocalOnnxEmbeddingModel}（不是复刻一份探针代码）。
 * <p>
 * 比三件事：
 *   1. batch —— {@code embedAll(20 条)} 的向量 vs Python 金标准
 *   2. single —— 逐条 {@code embed()} 的向量 vs 金标准
 *   3. batch 与 single 是否一致（批次填充不变性）
 * <p>
 * 第 3 条不是凑数：embedAll 会按 MAX_BATCH_SIZE=16 切成两批，两批的批内最长长度不同
 * （第二批含那条被截断到 512 的超长样本）。如果实现里 padding/mask 有偏差，
 * 这两批之间就会不一致，而单看任一批都发现不了。
 * <p>
 * 用法：java VerifyLocalModel &lt;model.onnx&gt; &lt;tokenizer.json&gt; &lt;texts.txt&gt; &lt;out.json&gt;
 */
public class VerifyLocalModel {

    public static void main(String[] args) throws Exception {
        Path modelPath = Paths.get(args[0]).toAbsolutePath();
        Path tokenizerPath = Paths.get(args[1]).toAbsolutePath();
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
            texts.add(new String(Base64.getDecoder().decode(line.substring(t + 1)), StandardCharsets.UTF_8));
        }
        System.out.printf("样本 %d 条%n", texts.size());

        try (LocalOnnxEmbeddingModel m = new LocalOnnxEmbeddingModel(modelPath, tokenizerPath, 8)) {
            long t0 = System.currentTimeMillis();
            boolean ok = m.isAvailable();
            System.out.printf("isAvailable=%s dim=%d initError=%s 首次初始化 %d ms%n",
                    ok, m.dimension(), m.initError(), System.currentTimeMillis() - t0);
            if (!ok) {
                System.out.println("初始化失败，无法继续");
                return;
            }

            t0 = System.currentTimeMillis();
            List<float[]> batch = m.embedAll(texts);
            System.out.printf("embedAll(%d 条) 用时 %d ms，返回 %d 条，维度 %d%n",
                    texts.size(), System.currentTimeMillis() - t0, batch.size(),
                    batch.isEmpty() ? -1 : batch.get(0).length);

            List<float[]> singles = new ArrayList<>(texts.size());
            t0 = System.currentTimeMillis();
            for (String t : texts) {
                singles.add(m.embed(t));
            }
            System.out.printf("逐条 embed() 用时 %d ms%n", System.currentTimeMillis() - t0);

            // 返回长度必须与入参一致 —— 否则调用方按下标取向量会整体错位
            if (batch.size() != texts.size()) {
                System.out.println("!! embedAll 返回条数与入参不一致: " + batch.size() + " vs " + texts.size());
            }

            try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
                w.write("{\"dim\": " + m.dimension() + ",\n\"batch\": ");
                writeRows(w, sids, batch);
                w.write(",\n\"single\": ");
                writeRows(w, sids, singles);
                w.write("}\n");
            }
            System.out.println("落盘 " + outFile);
        }
    }

    private static void writeRows(BufferedWriter w, List<String> sids, List<float[]> vecs) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sids.size(); i++) {
            sb.append(i == 0 ? "{\"id\":\"" : ",{\"id\":\"").append(sids.get(i)).append("\",\"vec\":[");
            float[] v = vecs.get(i);
            for (int j = 0; j < v.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(v[j]);
            }
            sb.append("]}");
        }
        w.write(sb.append(']').toString());
    }
}
