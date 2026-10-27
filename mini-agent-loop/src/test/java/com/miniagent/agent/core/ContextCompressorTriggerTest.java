package com.miniagent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩的触发、可观测性与「工程压缩优先」的两步式。
 *
 * <p>阈值语义与 WorkBuddy 对齐：{@code pre-message-threshold}（默认 0.5）是常规触发点，
 * {@code emergency-threshold}（默认 0.9）是无视冷却的紧急档；
 * 工程压缩结果不超过窗口的 {@code engineering-sufficiency-ratio}（默认 0.15）时跳过 LLM 摘要。</p>
 *
 * <p>测试里把阈值压到 0.01 强制走通压缩路径，而不是调低生产阈值 ——
 * 生产阈值调低会让正常任务过早丢上下文。</p>
 */
class ContextCompressorTriggerTest {

    private static ContextCompressor compressor(double preMessage, double emergency) throws Exception {
        ContextCompressor c = new ContextCompressor();
        set(c, "tokenEstimator", new TokenEstimator());
        set(c, "preMessageThreshold", preMessage);
        set(c, "emergencyThreshold", emergency);
        set(c, "engineeringSufficiencyRatio", 0.15);
        set(c, "llmSummaryEnabled", false);
        return c;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = ContextCompressor.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 结构真实的会话：1 条 system + 首轮用户意图 + 多轮「用户追加 → 模型正文 → 工具调用 → 工具结果」。
     *
     * <p>必须带真实工具往返，因为压缩要保住 {@code tool_call}/{@code tool_result} 配对，
     * 也要保住当前轮 —— 只用 AiMessage 堆出来的假会话测不到这两条。</p>
     */
    private static List<ChatMessage> conversation(int rounds) {
        List<ChatMessage> m = new ArrayList<>();
        m.add(new SystemMessage("你是 MiniAgent，按步骤完成用户任务。" + "系统提示内容。".repeat(20)));
        m.add(new UserMessage("帮我把前端改造成三区一轨工作台，并接真实 API。"));
        for (int i = 0; i < rounds; i++) {
            m.add(new UserMessage("第 " + i + " 步：继续推进。" + "按计划执行。".repeat(8)));
            m.add(AiMessage.from("我先核对接口字段。" + "核对内容。".repeat(16)));
            m.add(AiMessage.from(List.of(ToolExecutionRequest.builder()
                    .id("c" + i).name("read_file")
                    .arguments("{\"path\":\"src/page-" + i + ".html\"}")
                    .build())));
            m.add(ToolExecutionResultMessage.from("c" + i, "read_file", "文件内容".repeat(200)));
        }
        return m;
    }

    @Test
    void compressesWhenOverThresholdAndReportsRealTokens() throws Exception {
        ContextCompressor c = compressor(0.01, 0.99);
        List<ChatMessage> original = conversation(10);

        ContextCompressor.CompressResult r = c.maybeCompress(original, 2000, "s-compress-test", 0);

        assertTrue(r.compressed(), "超过阈值必须真的压缩，而不是原样返回");
        assertTrue(r.beforeTokens() > r.afterTokens(),
                "afterTokens 必须小于 beforeTokens，指标要真实（旧实现是 0→0）");
        assertEquals(original.size(), r.beforeCount(), "beforeCount 应是压缩前的条数");
        assertTrue(r.afterCount() < r.beforeCount(), "压缩后条数应减少");
        assertTrue(r.savedPercent() > 0, "节省比例应大于 0");
        assertTrue(
                r.messages().stream().anyMatch(x -> x instanceof SystemMessage sm
                        && sm.text().contains("历史对话摘要")),
                "必须注入交接摘要，否则模型丢失中段做过什么");
    }

    @Test
    void leavesConversationUntouchedWhenBelowThreshold() throws Exception {
        ContextCompressor c = compressor(0.99, 0.995);
        List<ChatMessage> original = conversation(10);

        ContextCompressor.CompressResult r = c.maybeCompress(original, 200000, "s-nocompress-test", 0);

        assertFalse(r.compressed(), "未到阈值不应压缩");
        assertSame(original, r.messages(), "未压缩必须原样返同一个列表");
        assertEquals(r.beforeTokens(), r.afterTokens(), "未压缩时前后 token 相同");
    }

    /**
     * 真实 usage 校准：本地估算偏小时以真实值为准。
     * 生产上的表现是「provider 分词比 0.7/0.3 估得更贵」，此时压缩必须按真实值提前触发。
     */
    @Test
    void observedTokensCanTriggerCompressionWhenLocalEstimateIsLow() throws Exception {
        // 紧急档设到 1.1 永不命中，确保走的是常规 pre-message 判据
        ContextCompressor c = compressor(0.85, 1.1);
        List<ChatMessage> original = conversation(10);
        int localTokens = new TokenEstimator().estimateMessages(original);
        int window = localTokens * 2;

        ContextCompressor.CompressResult byLocal = c.maybeCompress(original, window, "s-observed-local", 0);
        assertFalse(byLocal.compressed(),
                "本地估算占比约 50%，低于 0.85，不该压缩");

        ContextCompressor.CompressResult byObserved =
                c.maybeCompress(original, window, "s-observed-api", localTokens * 6);
        assertTrue(byObserved.compressed(),
                "真实 usage 报 6 倍本地估算时必须压缩，否则判据系统性偏晚");
    }

    /**
     * 当前轮不能被压掉：最近一条用户消息之后的工具往返是推理现场，
     * 换成摘要会让模型丢掉刚拿到的工具结果、重新调用一次工具。
     */
    @Test
    void keepsCurrentTurnToolRoundVerbatim() throws Exception {
        ContextCompressor c = compressor(0.01, 0.99);
        List<ChatMessage> original = conversation(8);
        ChatMessage lastResult = original.get(original.size() - 1);

        ContextCompressor.CompressResult r = c.maybeCompress(original, 2000, "s-keep-tail", 0);

        assertTrue(r.compressed(), "前置条件：应当发生压缩");
        assertTrue(r.messages().contains(lastResult),
                "当前轮的工具结果必须原样保留，不能替换成占位或摘要");
        assertTrue(r.messages().stream().noneMatch(x -> x instanceof ToolExecutionResultMessage tr
                        && tr.text() != null && tr.text().length() > 4000),
                "被压掉的历史里的大段工具原文不应残留（新实现把它们交给交接摘要）");
    }

    /**
     * 两步压缩：工程压缩（纯字符串重排）已经足够小时，即使开了 LLM 摘要也不得调用模型。
     * 用「一旦被调用就置位」的假模型来证明它没被调用。
     */
    @Test
    void skipsLlmSummaryWhenEngineeringCompressionIsEnough() throws Exception {
        ContextCompressor c = compressor(0.01, 0.99);
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        set(c, "llmSummaryEnabled", true);
        // 目标比放到 1.0：压缩后总量必然低于它 → 工程压缩「够用」
        set(c, "engineeringSufficiencyRatio", 1.0);
        set(c, "chatModel", stubModel(llmCalled));

        ContextCompressor.CompressResult r =
                c.maybeCompress(conversation(10), 2000, "s-eng-enough", 0);

        assertTrue(r.compressed(), "该压还是要压，只是不升级到 LLM");
        assertFalse(llmCalled.get(),
                "工程压缩已足够时不得再花一次 LLM 调用 —— 这是两步压缩的核心收益");
        assertTrue(
                r.messages().stream().anyMatch(x -> x instanceof SystemMessage sm
                        && sm.text().contains("已移除中间")),
                "此时的摘要应是结构化交接 stub（列出写过哪些文件/哪些命令失败）");
    }

    /** 工程压缩仍偏大时必须升级到 LLM 摘要，不能被「跳过」逻辑误伤。 */
    @Test
    void escalatesToLlmWhenEngineeringCompressionIsStillTooLarge() throws Exception {
        ContextCompressor c = compressor(0.01, 0.99);
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        set(c, "llmSummaryEnabled", true);
        // 目标比 0.001：窗口 2000 → 目标 2 token，任何结果都超 → 必然升级
        set(c, "engineeringSufficiencyRatio", 0.001);
        set(c, "chatModel", stubModel(llmCalled));

        ContextCompressor.CompressResult r =
                c.maybeCompress(conversation(10), 2000, "s-eng-toolarge", 0);

        assertTrue(r.compressed());
        assertTrue(llmCalled.get(), "工程压缩不够小时必须调用 LLM 摘要");
        assertTrue(
                r.messages().stream().anyMatch(x -> x instanceof SystemMessage sm
                        && sm.text().contains("LLM 生成的交接摘要")),
                "应当采用 LLM 产出的摘要而不是 stub");
    }

    /**
     * 成功压缩后跳过一次常规机会，避免「当前轮本身接近阈值 → 每轮压一次」。
     * 但紧急档必须能穿透这条护栏，否则上下文继续增长时会被自己的冷却卡死。
     */
    @Test
    void successfulCompressionSkipsNextRoundButEmergencyOverrides() throws Exception {
        ContextCompressor c = compressor(0.01, 0.9);
        List<ChatMessage> original = conversation(10);
        // 窗口取 1.2 倍本地估算：占比约 83% —— 高于常规阈值、低于紧急阈值
        int window = (int) (new TokenEstimator().estimateMessages(original) * 1.2);

        assertTrue(c.maybeCompress(original, window, "s-cooldown", 0).compressed(),
                "前置条件：第一次必须压缩");
        assertFalse(c.maybeCompress(original, window, "s-cooldown", 0).compressed(),
                "紧接着的常规触发应被跳过，否则会退化成每轮一次 LLM 摘要");

        ContextCompressor.CompressResult emergency =
                c.maybeCompress(original, window, "s-cooldown", (int) (window * 0.95));
        assertTrue(emergency.compressed(), "紧急档（90%）必须穿透冷却与连续无效护栏");
    }

    /**
     * 真实运行暴露的缺陷回归：一句话任务 + 几十轮工具往返。
     *
     * <p>旧实现按「最近一条用户消息」切尾部，这种会话里最近一条用户消息就是第一条，
     * 于是整段工具历史都被当成「当前轮」留下，被压缩的只有一两条消息 ——
     * 实测压缩后 17195 → 17484 token（反而更大），触发回退 + 冷却 + 白花一次 LLM 摘要。
     * 正确口径是尾部只占窗口的一小段，其余全部折叠。</p>
     */
    @Test
    void foldsLongToolHistoryEvenWhenThereIsOnlyOneUserMessage() throws Exception {
        ContextCompressor c = compressor(0.5, 0.99);
        List<ChatMessage> original = conversation(20);
        int before = new TokenEstimator().estimateMessages(original);
        // 窗口等于当前用量：必然触发；尾部预算按比例只有窗口的一小段
        ContextCompressor.CompressResult r = c.maybeCompress(original, before, "s-single-turn", 0);

        assertTrue(r.compressed(), "这种会话必须能压下去，否则压缩等于不生效");
        assertTrue(r.afterTokens() * 2 < r.beforeTokens(),
                "压缩后应不到原来一半（实际 " + r.beforeTokens() + " → " + r.afterTokens() + "）");
        assertTrue(r.beforeCount() - r.afterCount() > 3,
                "应折叠多轮工具往返，而不是只压一两条（实际折叠 "
                        + (r.beforeCount() - r.afterCount()) + " 条）");
    }

    /**
     * 「工程压缩够用」只看摘要自身大小，不看头部 + 尾部 + 摘要的总量。
     *
     * <p>头部是不可压缩的 system/taskPlan，长任务里常有几千 token。把它计进目标，
     * 「够用」条件就永远不成立 —— 每次压缩都白叫一次 LLM 摘要，而摘要再短也压不动 system prompt。</p>
     */
    @Test
    void sufficiencyIsJudgedOnSummaryAloneNotOnUncompressibleHead() throws Exception {
        ContextCompressor c = compressor(0.4, 0.99);
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        set(c, "llmSummaryEnabled", true);
        set(c, "chatModel", stubModel(llmCalled));

        // 头部本身就占满窗口的大部分：总量判据下必然「不够」，摘要判据下必然「够」
        List<ChatMessage> original = new ArrayList<>();
        original.add(new SystemMessage("你是 MiniAgent。" + "系统提示内容。".repeat(700)));
        original.add(new UserMessage("读几个文件并汇总。"));
        for (int i = 0; i < 8; i++) {
            original.add(new UserMessage("第 " + i + " 步继续。" + "按计划执行。".repeat(6)));
            original.add(AiMessage.from(List.of(ToolExecutionRequest.builder()
                    .id("c" + i).name("read_file").arguments("{\"path\":\"f" + i + ".java\"}").build())));
            original.add(ToolExecutionResultMessage.from("c" + i, "read_file", "文件内容".repeat(200)));
        }
        int before = new TokenEstimator().estimateMessages(original);

        ContextCompressor.CompressResult r = c.maybeCompress(original, before, "s-head-heavy", 0);

        assertTrue(r.compressed(), "前置条件：应当发生压缩");
        assertFalse(llmCalled.get(),
                "摘要只有几百 token，远低于窗口的 15%，不该因为 system prompt 大就去调模型");
    }

    /** 记录是否被调用，返回一句可识别的摘要。 */
    private static ChatModel stubModel(AtomicBoolean called) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                called.set(true);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("LLM 生成的交接摘要：已完成 10 步，无失败命令。"))
                        .build();
            }
        };
    }
}
