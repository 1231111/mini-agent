package com.miniagent.config.cloud;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单价换算与缓存扣减。
 *
 * <p>核心断言是「缓存那部分不能按普通输入价重复计费」：这是最容易写错、又最难在页面上
 * 发现的地方——错的方向是费用偏高，而偏高在单个调用上看不出异常。</p>
 */
class ConfiguredModelPricingTest {

    private static ModelPricingProperties.Price price(String input, String cached, String output) {
        return new ModelPricingProperties.Price(
                new BigDecimal(input), new BigDecimal(cached), new BigDecimal(output));
    }

    private static ModelPricingProperties properties(ModelPricingProperties.Price fallback,
                                                     Map<String, ModelPricingProperties.Price> models) {
        ModelPricingProperties props = new ModelPricingProperties();
        props.setModels(new LinkedHashMap<>(models));
        props.setFallback(fallback);
        return props;
    }

    @Test
    void cachedTokensAreBilledSeparatelyFromInput() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("deepseek-flash", price("2", "0.2", "8"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        // 10000 输入其中 4000 命中缓存：6000×2 + 4000×0.2 + 1000×8 = 12000+800+8000 = 20800
        long micros = pricing.costMicros("deepseek-flash", 10_000, 4_000, 1_000);
        assertEquals(20_800, micros);

        // 若把缓存按全价算会得到 10000×2 + 1000×8 = 28000，明显偏高。
        assertTrue(micros < 28_000);
    }

    @Test
    void noCacheEqualsFullInputPrice() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("m", price("2", "0.2", "8"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        assertEquals(20_000, pricing.costMicros("m", 10_000, 0, 0));
    }

    @Test
    void modelNameMatchIsCaseInsensitive() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("deepseek-flash", price("2", "0.2", "8"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        assertEquals(2_000, pricing.costMicros("DeepSeek-Flash", 1_000, 0, 0));
        assertTrue(pricing.priced("DEEPSEEK-FLASH"));
    }

    @Test
    void unknownModelFallsBack() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("known", price("1", "0.1", "2"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(
                properties(price("3", "0.3", "12"), models));

        assertEquals(3_000, pricing.costMicros("unknown-model", 1_000, 0, 0));
        assertTrue(pricing.priced("unknown-model"));
    }

    @Test
    void unknownModelWithoutFallbackIsZeroAndCounted() {
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(
                properties(null, Map.of()));

        assertEquals(0, pricing.costMicros("whatever", 1_000, 0, 1_000));
        assertFalse(pricing.priced("whatever"));
        assertEquals(1, pricing.unpricedCalls());
    }

    @Test
    void allZeroPriceIsTreatedAsUnconfigured() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("free-looking", price("0", "0", "0"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        // 全 0 的单价不是「免费模型」，是「没配」；否则成本统计会悄悄失效。
        assertEquals(0, pricing.costMicros("free-looking", 10_000, 0, 1_000));
        assertFalse(pricing.priced("free-looking"));
        assertEquals(1, pricing.unpricedCalls());
    }

    @Test
    void cachedGreaterThanInputIsClamped() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("m", price("2", "0.2", "8"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        // 上游若报出 cached > input，钳到 input 以内，避免出现负的计费输入。
        // 钳制之后整段输入都算缓存命中：1000 × 0.2（缓存价）= 200，而不是 1000 × 2（全价）。
        assertEquals(200, pricing.costMicros("m", 1_000, 9_999, 0));
    }

    @Test
    void negativeValuesAreTreatedAsZero() {
        Map<String, ModelPricingProperties.Price> models = new LinkedHashMap<>();
        models.put("m", price("2", "0.2", "8"));
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(properties(null, models));

        assertEquals(0, pricing.costMicros("m", -100, -100, -100));
    }

    @Test
    void blankModelUsesFallback() {
        ConfiguredModelPricing pricing = new ConfiguredModelPricing(
                properties(price("3", "0.3", "12"), Map.of()));

        assertEquals(3_000, pricing.costMicros("", 1_000, 0, 0));
        assertEquals(3_000, pricing.costMicros(null, 1_000, 0, 0));
    }
}
