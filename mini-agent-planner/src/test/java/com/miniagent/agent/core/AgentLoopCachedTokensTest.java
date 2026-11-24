package com.miniagent.agent.core;

import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缓存命中 token 的提取。
 *
 * <p>这组断言针对的是一个"拿到了但没人读"的静默故障：上游明明返回了
 * {@code prompt_tokens_details.cached_tokens}，代码却只取 input/output，
 * 于是缓存部分按全价计费，请求成功、日志无异常，只有账单偏高。</p>
 *
 * <p>唯一能发现它的方式就是断言命中值被原样读出来 —— 所以这里刻意用了一次真实
 * 生产量的数字（19666 输入、19584 命中）而不是随手编的小数字。</p>
 *
 * <p>另一组断言覆盖"未采集"：非 OpenAI 客户端、details 缺失、cachedTokens 为 null，
 * 三种情况都必须返回 -1，而不是 0。0 意味着"确认零命中"，会把"不知道"伪装成"没命中"。</p>
 */
class AgentLoopCachedTokensTest {

    private static OpenAiTokenUsage openAiUsage(Integer cachedTokens) {
        OpenAiTokenUsage.Builder builder = OpenAiTokenUsage.builder()
                .inputTokenCount(19_666)
                .outputTokenCount(82)
                .totalTokenCount(19_748);
        if (cachedTokens != null) {
            builder.inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                    .cachedTokens(cachedTokens)
                    .build());
        }
        return builder.build();
    }

    @Test
    void realCacheHitIsReadVerbatim() {
        assertEquals(19_584, AgentLoop.cachedInputTokens(openAiUsage(19_584)));
    }

    @Test
    void confirmedZeroHitStaysZero() {
        assertEquals(0, AgentLoop.cachedInputTokens(openAiUsage(0)));
    }

    @Test
    void missingDetailsMeansUnknown() {
        // details 整段缺失：上游没返回缓存信息。
        assertEquals(-1, AgentLoop.cachedInputTokens(openAiUsage(null)));
    }

    @Test
    void nullCachedTokensMeansUnknown() {
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(1_000)
                .outputTokenCount(10)
                .totalTokenCount(1_010)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(null)
                        .build())
                .build();
        assertEquals(-1, AgentLoop.cachedInputTokens(usage));
    }

    @Test
    void plainTokenUsageMeansUnknown() {
        // 换 Anthropic 或其他非 OpenAI 兼容客户端时，tokenUsage() 是父类型。
        assertEquals(-1, AgentLoop.cachedInputTokens(new TokenUsage(1_000, 10)));
    }

    @Test
    void nullUsageMeansUnknown() {
        assertEquals(-1, AgentLoop.cachedInputTokens(null));
    }
}
