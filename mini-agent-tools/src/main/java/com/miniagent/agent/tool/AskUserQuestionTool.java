package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.AskUserQuestionParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 用户交互工具：主动向用户提问获取信息
 */
@Slf4j
@Component
public class AskUserQuestionTool {

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    @PostConstruct
    public void register() {
        // 使用新的实体类注册方式
        toolRegistry.register(
                "ask_user_question",
                "主动向用户提问并等待回答；可附带选项、可允许多选。\n" +
                "什么时候该用：缺少只有用户才知道的关键信息（偏好、目标、凭据），且猜错的代价明显大于问一次的代价。\n" +
                "什么时候不该用：答案能从上下文、文件或工具里查到的，自己去查，不要拿提问代替探索；\n" +
                "能用合理默认值推进的，先做，把假设写进结果里。\n" +
                "一次把想问的问清楚，不要让用户反复回答。",
                AskUserQuestionParams.class,
                this::handle
        );
    }

    private String handle(AskUserQuestionParams params) {
        try {
            String question = params.getQuestion();
            String options = params.getOptions();
            boolean multiSelect = params.isMultiSelectOrDefault();

            log.info("向用户提问: {} (多选: {})", question, multiSelect);

            // 构建问题JSON
            Map<String, Object> questionData = Map.of(
                    "question", question,
                    "options", options != null ? options : "[]",
                    "multiSelect", multiSelect
            );

            String questionJson = MAPPER.writeValueAsString(questionData);

            // TODO: 这里需要与前端WebSocket或REST API集成
            // 当前实现为阻塞等待，实际应该通过消息队列或回调机制
            // 示例：通过WebSocket发送问题，等待用户回答

            // 模拟等待用户回答（实际应替换为真正的异步等待机制）
            CompletableFuture<String> userAnswer = new CompletableFuture<>();

            // 注册回调，当用户回答时完成Future
            // userAnswerWebSocketHandler.registerCallback(questionId, userAnswer);

            // 等待用户回答（带超时）
            try {
                String answer = userAnswer.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("用户回答: {}", answer);
                return MAPPER.writeValueAsString(Map.of(
                        "success", true,
                        "question", question,
                        "answer", answer
                ));
            } catch (Exception e) {
                return MAPPER.writeValueAsString(Map.of(
                        "success", false,
                        "error", "等待用户回答超时或被中断"
                ));
            }

        } catch (Exception e) {
            log.error("提问工具执行失败", e);
            return "{\"success\":false,\"error\":\"提问工具执行失败: " + e.getMessage() + "\"}";
        }
    }
}