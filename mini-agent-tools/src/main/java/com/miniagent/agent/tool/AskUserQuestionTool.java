package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.AskUserQuestionParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问。真正等待由 AgentLoop 让出循环，本工具只规范化参数。
 */
@Slf4j
@Component
public class AskUserQuestionTool {

    public static final String TOOL_NAME = "ask_user_question";

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        toolRegistry.register(
                TOOL_NAME,
                "主动向用户提问并等待回答；可附带选项、可允许多选。\n"
                        + "什么时候该用：缺少只有用户才知道的关键信息（偏好、目标、凭据），"
                        + "且猜错的代价明显大于问一次的代价。\n"
                        + "什么时候不该用：答案能从上下文、文件或工具里查到的，自己去查；"
                        + "能用合理默认值推进的，先做，把假设写进结果里。\n"
                        + "一次把想问的问清楚，不要让用户反复回答。",
                AskUserQuestionParams.class,
                this::handle
        );
    }

    private String handle(AskUserQuestionParams params) {
        try {
            String json = params == null ? "{}" : MAPPER.writeValueAsString(params);
            log.info("向用户提问: {}", displayText(json));
            return sseJson(json);
        } catch (Exception e) {
            log.error("提问工具参数无效", e);
            return "{\"error\":\"提问工具参数无效\"}";
        }
    }

    public static String sseJson(String argumentsJson) {
        try {
            return MAPPER.writeValueAsString(payload(argumentsJson));
        } catch (Exception e) {
            return "{\"question\":\"\"}";
        }
    }

    public static String displayText(String argumentsJson) {
        Map<String, Object> p = payload(argumentsJson);
        String question = String.valueOf(p.getOrDefault("question", "")).trim();
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) p.getOrDefault("options", List.of());
        StringBuilder sb = new StringBuilder();
        if (question.isEmpty()) {
            sb.append("需要你补充一点信息才能继续。");
        } else {
            sb.append(question);
        }
        if (!options.isEmpty()) {
            sb.append('\n');
            for (String opt : options) {
                sb.append("\n- ").append(opt);
            }
            sb.append("\n\n点选选项，或直接在输入框回复。");
        }
        return sb.toString();
    }

    static Map<String, Object> payload(String argumentsJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", "");
        out.put("options", List.of());
        out.put("multiSelect", false);
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject()) {
                return out;
            }
            out.put("question", node.path("question").asText(""));
            JsonNode opt = node.get("options");
            if (opt != null && !opt.isNull()) {
                if (opt.isArray()) {
                    out.put("options", parseOptions(opt.toString()));
                } else {
                    out.put("options", parseOptions(opt.asText()));
                }
            }
            out.put("multiSelect", node.path("multiSelect").asBoolean(false));
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    static List<String> parseOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node.isArray()) {
                List<String> out = new ArrayList<>();
                node.forEach(item -> {
                    if (item != null && !item.isNull()) {
                        String text = item.isTextual() ? item.asText() : item.toString();
                        if (!text.isBlank()) {
                            out.add(text);
                        }
                    }
                });
                return List.copyOf(out);
            }
            if (node.isTextual()) {
                return parseOptions(node.asText());
            }
        } catch (Exception ignored) {
            // 非 JSON 时当作单一选项
        }
        return List.of(raw.trim());
    }
}
