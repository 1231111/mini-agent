package com.miniagent.config.model;
import java.util.Optional;

/**
 * 解析后的有效模型连接参数（含明文 apiKey，仅供工厂内部建连，禁止直接回传前端）。
 *
 * <p>{@code contextWindowTokens} 是该模型**厂商侧**的上下文窗口（0 = 未知/未配置）。
 * 它是修"工作窗口写死 512k"的关键输入：配了 32k/128k 的模型时，循环必须按模型窗口压缩，
 * 否则 512k 的阈值永远不触发，请求会被上游以 context-length 400 拒绝 ——
 * 而那类 400 此前还会被误判成"模型连接异常"。</p>
 */
public record EffectiveModelSettings(
        String presetId,
        String label,
        String baseUrl,
        String modelName,
        String apiKey,
        int contextWindowTokens
) {
    /** 兼容旧构造点：未声明窗口按 0（未知）处理。 */
    public EffectiveModelSettings(String presetId, String label, String baseUrl,
                                  String modelName, String apiKey) {
        this(presetId, label, baseUrl, modelName, apiKey, 0);
    }

    public String cacheKey() {
        return baseUrl + "|" + modelName + "|" + (Optional.ofNullable(apiKey).orElse(""));
    }

    /** 厂商窗口是否已知。未知时上层回退到配置的工作窗口。 */
    public boolean hasKnownContextWindow() {
        return contextWindowTokens > 0;
    }
}
