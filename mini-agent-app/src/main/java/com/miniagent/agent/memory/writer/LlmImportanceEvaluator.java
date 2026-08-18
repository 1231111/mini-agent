package com.miniagent.agent.memory.writer;

import com.miniagent.common.StringUtils;
import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.writer.ImportanceEvaluator;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 兜底的重要性评估器（第二层）。
 * 只在规则评估结果不确定（0.2~0.5）时调用，控制成本。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.llm-evaluator-enabled", havingValue = "true")
public class LlmImportanceEvaluator implements ImportanceEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LlmImportanceEvaluator.class);

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired
    private RuleBasedImportanceEvaluator ruleEvaluator;

    @Value("${agent.memory.llm-evaluator-threshold-low:0.2}")
    private double thresholdLow;

    @Value("${agent.memory.llm-evaluator-threshold-high:0.5}")
    private double thresholdHigh;

    @Override
    public double evaluate(AgentEvent event) {
        double ruleScore = ruleEvaluator.evaluate(event);

        // 规则评估明确时直接返回
        if (ruleScore < thresholdLow || ruleScore > thresholdHigh) {
            return ruleScore;
        }

        // 不确定区间才调 LLM
        if (chatModel == null) {
            return ruleScore;
        }

        try {
            return llmEvaluate(event);
        } catch (Exception e) {
            log.warn("LLM 重要度评估失败，回退规则分数: {}", e.getMessage());
            return ruleScore;
        }
    }

    private double llmEvaluate(AgentEvent event) {
        String prompt = """
            判断以下 Agent 事件是否值得长期记住。返回 0.0~1.0 的分数。
            高分 = 必须记住（失败经验、用户偏好、可复用方法）
            低分 = 不需要记住（常规操作、临时状态）

            事件类型: %s
            状态: %s
            执行者: %s
            内容: %s

            只返回一个数字（0.0~1.0），不要解释。""".formatted(
                event.getEventType(),
                event.getStatus(),
                event.getActor(),
                truncate(event.getPayload() != null ? event.getPayload().toString() : "", 500)
            );

        ChatRequest request = ChatRequest.builder()
            .messages(List.of(
                SystemMessage.from("你是一个记忆评估器。只返回 0.0 到 1.0 之间的数字。"),
                UserMessage.from(prompt)
            ))
            .build();

        String response = chatModel.chat(request).aiMessage().text().trim();
        return parseScore(response);
    }

    private double parseScore(String text) {
        try {
            // 提取第一个数字
            String num = text.replaceAll("[^0-9.]", "");
            if (num.isEmpty()) return 0.3;
            double score = Double.parseDouble(num);
            return Math.max(0.0, Math.min(1.0, score));
        } catch (NumberFormatException e) {
            return 0.3;
        }
    }

    private String truncate(String s, int maxLen) {
        return StringUtils.truncate(s, maxLen);
    }
}
