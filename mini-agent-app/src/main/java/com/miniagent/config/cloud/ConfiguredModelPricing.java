package com.miniagent.config.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按配置的单价表把 token 换算成微元。
 *
 * <h2>换算为什么是「单价 × token 数」</h2>
 *
 * <p>单价单位是「元 / 百万 token」，返回值单位是「百万分之一元」（micros）。设单价 p 元/百万，
 * 用量 t 个 token：</p>
 *
 * <pre>
 * 费用（元） = p × t / 1_000_000
 * 费用（micros） = 费用（元） × 1_000_000 = p × t
 * </pre>
 *
 * <p>两个 1_000_000 正好抵消，所以直接乘。这不是漏了除法，把 p 当「每 token 多少 micros」看
 * 就通了：p=2 意味着每百万 token 2 元，即每 token 2 micros。</p>
 *
 * <h2>缓存部分必须从输入里扣出来</h2>
 *
 * <p>{@code cachedInputTokens} 是 {@code inputTokens} 的<b>子集</b>，不是额外量。
 * 不扣直接用全额 input 乘普通价，等于把缓存那部分按贵价收两遍中的一遍——
 * 而缓存价通常只有普通价的十分之一，结果会系统性偏高。</p>
 */
@Component
@EnableConfigurationProperties(ModelPricingProperties.class)
public class ConfiguredModelPricing {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredModelPricing.class);

    private final ModelPricingProperties properties;
    /** 没匹配到单价的模型被计价了几次。用于判断「页面上费用是 0」是不是因为漏配。 */
    private final AtomicLong unpricedCalls = new AtomicLong();

    public ConfiguredModelPricing(ModelPricingProperties properties) {
        this.properties = properties;
        if (properties.getModels().isEmpty() && properties.getFallback() == null) {
            // 启动时提示一次即可：没配价格表时费用恒为 0，页面会显示 ¥0.0000，
            // 容易让人以为统计坏了，实际是没配置。
            log.info("未配置模型单价（agent.usage.pricing），调用费用将记为 0");
        }
    }

    /**
     * 计算一次调用的费用（micros）。
     *
     * @param model              模型名，取 {@code ChatResponseMetadata.modelName()}
     * @param inputTokens        输入 token 总数（含缓存命中部分）
     * @param cachedInputTokens  其中缓存命中的部分
     * @param outputTokens       输出 token
     * @return micros；未匹配到任何单价时返回 0
     */
    public long costMicros(String model, long inputTokens, long cachedInputTokens, long outputTokens) {
        ModelPricingProperties.Price price = resolve(model);
        if (price == null) {
            long count = unpricedCalls.incrementAndGet();
            if (count <= 3 || count % 500 == 0) {
                log.warn("模型未配置单价，费用记为 0: model={}（累计 {} 次）。"
                        + "补 agent.usage.pricing.models 或 fallback 后这条会消失。", model, count);
            }
            return 0;
        }

        long input = Math.max(0, inputTokens);
        long cached = Math.min(Math.max(0, cachedInputTokens), input);
        long billableInput = input - cached;
        long output = Math.max(0, outputTokens);

        BigDecimal micros = price.getInputPerMillion().multiply(BigDecimal.valueOf(billableInput))
                .add(price.getCachedInputPerMillion().multiply(BigDecimal.valueOf(cached)))
                .add(price.getOutputPerMillion().multiply(BigDecimal.valueOf(output)));
        return micros.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 是否能为该模型计价。用于调用方决定要不要相信这个 0。 */
    public boolean priced(String model) {
        return resolve(model) != null;
    }

    public long unpricedCalls() { return unpricedCalls.get(); }

    private ModelPricingProperties.Price resolve(String model) {
        if (model == null || model.isBlank()) return usable(properties.getFallback());
        ModelPricingProperties.Price exact = properties.getModels().get(model);
        if (usable(exact) != null) return exact;
        // 大小写差异很常见（Gateway 返回 DeepSeek-Flash，配置写 deepseek-flash）。
        for (var entry : properties.getModels().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(model)) {
                ModelPricingProperties.Price matched = usable(entry.getValue());
                if (matched != null) return matched;
            }
        }
        return usable(properties.getFallback());
    }

    /** 全 0 的单价等于没配，不能当成「免费模型」——那会让成本统计悄悄失效。 */
    private static ModelPricingProperties.Price usable(ModelPricingProperties.Price price) {
        return price != null && price.configured() ? price : null;
    }
}
