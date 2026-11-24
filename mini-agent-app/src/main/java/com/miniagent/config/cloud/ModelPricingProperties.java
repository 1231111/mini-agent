package com.miniagent.config.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型单价表（{@code agent.usage.pricing.*}）。
 *
 * <p>单位是<b>元 / 百万 token</b>，和主流服务商报价页一致。不要在这里写 "0.000002" 那种
 * 每 token 单价：手写小数位极易多写或漏写一个零，而错误方向是"账单差 10 倍"，
 * 不会报错也不会被测试发现。</p>
 *
 * <p>为什么分三个价：缓存命中的输入通常比普通输入便宜一个数量级。只配一个 input 价，
 * 会把缓存命中按原价收，长期系统性偏高。</p>
 *
 * <p>为什么需要 fallback：新模型上线时配置往往没跟上。没有 fallback 就记 0 元，
 * 用户看到"免费用"，比按偏高价格估算更糟——前者会让成本统计失去意义。</p>
 *
 * <h2>配置示例</h2>
 * <pre>
 * agent:
 *   usage:
 *     pricing:
 *       models:
 *         deepseek-flash:
 *           input-per-million: 2
 *           cached-input-per-million: 0.2
 *           output-per-million: 8
 *       fallback:
 *         input-per-million: 3
 *         cached-input-per-million: 0.3
 *         output-per-million: 12
 * </pre>
 */
@ConfigurationProperties(prefix = "agent.usage.pricing")
public class ModelPricingProperties {

    /** 模型名 → 单价。模型名按 {@code ChatResponseMetadata.modelName()} 的返回值精确匹配。 */
    private Map<String, Price> models = new LinkedHashMap<>();

    /** 未匹配到模型时使用的单价；为 null 表示不计价（cost 记 0 并可被观测到）。 */
    private Price fallback;

    public Map<String, Price> getModels() { return models; }
    public void setModels(Map<String, Price> models) {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }
    public Price getFallback() { return fallback; }
    public void setFallback(Price fallback) { this.fallback = fallback; }

    public static class Price {
        /** 普通输入，元 / 百万 token。 */
        private BigDecimal inputPerMillion = BigDecimal.ZERO;
        /** 缓存命中的输入，元 / 百万 token。应低于 {@code inputPerMillion}。 */
        private BigDecimal cachedInputPerMillion = BigDecimal.ZERO;
        /** 输出，元 / 百万 token。 */
        private BigDecimal outputPerMillion = BigDecimal.ZERO;

        public Price() {
        }

        public Price(BigDecimal inputPerMillion, BigDecimal cachedInputPerMillion,
                     BigDecimal outputPerMillion) {
            this.inputPerMillion = inputPerMillion;
            this.cachedInputPerMillion = cachedInputPerMillion;
            this.outputPerMillion = outputPerMillion;
        }

        public BigDecimal getInputPerMillion() { return inputPerMillion; }
        public void setInputPerMillion(BigDecimal inputPerMillion) {
            this.inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
        }
        public BigDecimal getCachedInputPerMillion() { return cachedInputPerMillion; }
        public void setCachedInputPerMillion(BigDecimal cachedInputPerMillion) {
            this.cachedInputPerMillion = cachedInputPerMillion == null
                    ? BigDecimal.ZERO : cachedInputPerMillion;
        }
        public BigDecimal getOutputPerMillion() { return outputPerMillion; }
        public void setOutputPerMillion(BigDecimal outputPerMillion) {
            this.outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
        }

        public boolean configured() {
            return inputPerMillion.signum() > 0
                    || cachedInputPerMillion.signum() > 0
                    || outputPerMillion.signum() > 0;
        }
    }
}
