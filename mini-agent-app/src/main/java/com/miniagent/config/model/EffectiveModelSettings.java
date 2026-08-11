package com.miniagent.config.model;

/**
 * 解析后的有效模型连接参数（含明文 apiKey，仅供工厂内部建连，禁止直接回传前端）。
 */
public record EffectiveModelSettings(
        String presetId,
        String label,
        String baseUrl,
        String modelName,
        String apiKey
) {
    public String cacheKey() {
        return baseUrl + "|" + modelName + "|" + (apiKey == null ? "" : apiKey);
    }
}
