package com.miniagent.agent.intent;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * L1：可配独立小模型做多轮意图 JSON 分类。端点与 prompt 来自 {@link IntentProperties}。
 */
@Slf4j
@Component
public class LlmIntentClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_SYSTEM = """
            你是意图路由器。只输出一行 JSON，禁止 markdown，禁止其它文字。
            字段必须齐全，枚举只能用下列值（勿自造）：
            intent: QUESTION|IMAGE_GENERATION|NEW_TASK|CONTINUE_TASK|REVIEW
            toolProfile: QUESTION|IMAGE|FULL
            needsWeb/needsFiles/needsImageGen/requiresStructuredPlan/shouldUseHistory: true|false
            taskGoal: 一句目标; confidence: 0到1小数; reason: 一句理由
            示例: {"intent":"NEW_TASK","taskGoal":"continue previous work","needsWeb":false,"needsFiles":true,"needsImageGen":false,"requiresStructuredPlan":false,"shouldUseHistory":true,"toolProfile":"FULL","confidence":0.8,"reason":"multi-turn"}
            规则:
            1. 不确定→intent=NEW_TASK,toolProfile=FULL
            2. 仅纯生图用 IMAGE；需要网页或写文件→FULL
            3. 继续上一轮→CONTINUE_TASK,shouldUseHistory=true,FULL
            4. 寒暄/能力询问→QUESTION
            """;

    @Autowired

    private IntentProperties props;
    private volatile ChatModel dedicatedModel;

    

    @PostConstruct
    void initDedicatedModel() {
        String modelName = props.getModelName();
        String baseUrl = props.getBaseUrl();
        String apiKey = props.getApiKey();
        if (blank(modelName) || blank(baseUrl) || blank(apiKey)) {
            log.warn("意图 L1 未配置独立模型（agent.intent.model-name/base-url/api-key），将跳过 L1");
            return;
        }
        Duration timeout = Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds()));
        dedicatedModel = OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(timeout))
                .apiKey(apiKey.trim())
                .baseUrl(baseUrl.trim())
                .modelName(modelName.trim())
                .timeout(timeout)
                .temperature(0.0)
                .returnThinking(false)
                .sendThinking(false)
                .build();
        log.info("意图分类独立小模型已就绪: model={}, baseUrl={}", modelName, baseUrl);
    }

    public boolean isEnabled() {
        return props.isLlmEnabled();
    }

    public boolean hasDedicatedModel() {
        return Objects.nonNull(dedicatedModel);
    }

    public Classification classify(String userMessage, boolean hasImage, List<ChatMessage> recentHistory) {
        if (!isEnabled() || Objects.isNull(dedicatedModel)) return null;
        String system = blank(props.getClassifierSystemPrompt()) ? DEFAULT_SYSTEM : props.getClassifierSystemPrompt();
        try {
            ChatResponse resp = dedicatedModel.chat(UserMessage.from(system + "\n\n" + buildPrompt(userMessage, hasImage, recentHistory)));
            String text = Objects.isNull(resp) || Objects.isNull(resp.aiMessage()) || Objects.isNull(resp.aiMessage().text())
                    ? "" : resp.aiMessage().text().trim();
            Classification c = parse(text);
            if (Objects.isNull(c)) {
                log.warn("意图 LLM 返回无法解析: {}", text.length() > 240 ? text.substring(0, 240) + "…" : text);
            }
            return c;
        } catch (Exception e) {
            log.warn("意图 LLM 调用失败，将回退启发式: {}", e.getMessage());
            return null;
        }
    }

    String buildPrompt(String userMessage, boolean hasImage, List<ChatMessage> recentHistory) {
        String historyBlock = formatHistory(recentHistory);
        return """
                【最近对话】
                %s

                【当前用户消息】
                %s

                【附加】
                hasImage=%s
                """.formatted(
                StringUtils.isBlank(historyBlock) ? "（无）" : historyBlock,
                Optional.ofNullable(userMessage).orElse(""),
                hasImage
        );
    }

    private String formatHistory(List<ChatMessage> recentHistory) {
        if (Objects.isNull(recentHistory) || recentHistory.isEmpty()) return "";
        int maxMsg = Math.max(1, props.getHistoryMaxMessages());
        int maxChars = Math.max(200, props.getHistoryMaxChars());
        int from = Math.max(0, recentHistory.size() - maxMsg);
        List<String> lines = new ArrayList<>();
        int chars = 0;
        for (int i = from; i < recentHistory.size(); i++) {
            ChatMessage m = recentHistory.get(i);
            String role;
            String body;
            if (m instanceof UserMessage um) {
                role = "用户";
                body = um.hasSingleText() ? um.singleText() : String.valueOf(um.contents());
            } else if (m instanceof AiMessage am) {
                role = "助手";
                body = Objects.isNull(am.text()) ? "" : am.text();
            } else {
                continue;
            }
            if (Objects.isNull(body)) body = "";
            body = body.replace('\r', ' ').replace('\n', ' ').trim();
            if (body.length() > 400) body = body.substring(0, 400) + "…";
            String line = role + ": " + body;
            if (chars + line.length() > maxChars) break;
            lines.add(line);
            chars += line.length();
        }
        return String.join("\n", lines);
    }

    static Classification parse(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        String json = extractJsonObject(raw);
        if (Objects.isNull(json)) return null;
        try {
            JsonNode n = MAPPER.readTree(json);
            String toolProfile = text(n, "toolProfile").toUpperCase(Locale.ROOT);
            if (!toolProfile.equals("QUESTION") && !toolProfile.equals("IMAGE") && !toolProfile.equals("FULL")) {
                toolProfile = "FULL";
            }
            return new Classification(
                    parseIntent(text(n, "intent")),
                    text(n, "taskGoal"),
                    bool(n, "needsWeb"),
                    bool(n, "needsFiles"),
                    bool(n, "needsImageGen"),
                    bool(n, "requiresStructuredPlan"),
                    bool(n, "shouldUseHistory"),
                    toolProfile,
                    n.path("confidence").isNumber() ? n.path("confidence").asDouble(0.5) : 0.5,
                    text(n, "reason")
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static IntentType parseIntent(String raw) {
        if (StringUtils.isBlank(raw)) return IntentType.NEW_TASK;
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return IntentType.NEW_TASK;
        }
    }

    private static boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return Objects.nonNull(v) && v.isBoolean() && v.asBoolean();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return Objects.isNull(v) || v.isNull() ? "" : v.asText("").trim();
    }

    private static boolean blank(String s) {
        return StringUtils.isBlank(s);
    }

    static String extractJsonObject(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence).trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return t.substring(start, end + 1);
    }

    public record Classification(
            IntentType intent,
            String taskGoal,
            boolean needsWeb,
            boolean needsFiles,
            boolean needsImageGen,
            boolean requiresStructuredPlan,
            boolean shouldUseHistory,
            String toolProfile,
            double confidence,
            String reason
    ) {}
}
