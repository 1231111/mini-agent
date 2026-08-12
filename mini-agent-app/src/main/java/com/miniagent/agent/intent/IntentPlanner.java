package com.miniagent.agent.intent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 意图控制面：L0 配置/MySQL 规则 → L1 可配小模型 → L2 启发式。
 * 场景词与工具白名单不进代码，见 {@link IntentProperties} / {@link IntentRuleRuntime}。
 */
@Slf4j
@Component
public class IntentPlanner {

    @Autowired
    private LlmIntentClassifier classifier;
    @Autowired
    private IntentProperties props;
    @Autowired
    private IntentRuleGate ruleGate;
    @Autowired
    private IntentSignalMatcher signals;
    @Autowired(required = false)
    private IntentHitLogService hitLogService;

    public IntentPlanner() {}

    public TaskPlan plan(ChatModel chatModel, String userMessage, boolean hasImage) {
        return plan(chatModel, userMessage, hasImage, List.of());
    }

    public TaskPlan plan(ChatModel chatModel, String userMessage, boolean hasImage,
                         List<ChatMessage> recentHistory) {
        long t0 = System.currentTimeMillis();
        String text = userMessage == null ? "" : userMessage.trim();
        double minConfidence = clamp(props.getMinConfidence());
        if (Objects.nonNull(hitLogService)) {
            hitLogService.begin(text, hasImage);
        }

        if (hasImage && text.length() <= props.getRules().getReviewMaxLen()) {
            return finish("L0", text, new TaskPlan(IntentType.REVIEW,
                    text.isBlank() ? "分析用户截图反馈" : text,
                    true, true, false, List.of(), List.of(), "rule:image-review", false),
                    null, t0);
        }

        TaskPlan gated = ruleGate.tryShortCircuit(text, hasImage);
        if (gated != null) {
            return finish("L0", text, gated, null, t0);
        }
        noteSkip("L0", "规则未命中，继续下层");

        if (classifier != null && classifier.isEnabled()) {
            LlmIntentClassifier.Classification c =
                    classifier.classify(text, hasImage, recentHistory);
            if (c != null && c.confidence() >= minConfidence) {
                return finish("L1", text, fromClassification(text, hasImage, c, minConfidence), c, t0);
            }
            if (c != null) {
                log.info("意图漏斗: layer=L1 低置信 conf={} < {}，回退 L2，reason={}",
                        String.format("%.2f", c.confidence()),
                        String.format("%.2f", minConfidence),
                        c.reason());
                noteSkip("L1", "小模型低置信 conf=" + String.format("%.2f", c.confidence())
                        + " < " + String.format("%.2f", minConfidence) + "，回退 L2");
            } else {
                noteSkip("L1", classifier.hasDedicatedModel()
                        ? "小模型无有效分类结果，回退 L2"
                        : "意图小模型未配置，跳过 L1");
            }
        } else {
            noteSkip("L1", "意图小模型未启用，跳过 L1");
        }

        return finish("L2", text, planByHeuristic(text, hasImage, recentHistory), null, t0);
    }

    private void noteSkip(String layer, String why) {
        if (Objects.nonNull(hitLogService)) hitLogService.recordSkip(layer, why);
    }

    private TaskPlan finish(String layer, String userText, TaskPlan plan,
                            LlmIntentClassifier.Classification c, long t0) {
        int toolCount = plan.allowedTools() == null ? -1 : plan.allowedTools().size();
        if (c != null) {
            log.info("意图漏斗: layer={} intent={} profile={} structured={} tools={} conf={} reason={}",
                    layer, plan.intent(), safeProfile(c), plan.requiresStructuredPlan(),
                    toolCount, String.format("%.2f", c.confidence()), plan.reason());
        } else {
            log.info("意图漏斗: layer={} intent={} structured={} tools={} reason={}",
                    layer, plan.intent(), plan.requiresStructuredPlan(), toolCount, plan.reason());
        }
        if (Objects.nonNull(hitLogService)) {
            hitLogService.record(layer, userText, plan, c, System.currentTimeMillis() - t0);
        }
        return plan;
    }

    private TaskPlan fromClassification(String text, boolean hasImage,
                                        LlmIntentClassifier.Classification c,
                                        double minConfidence) {
        IntentType intent = c.intent() == null ? IntentType.NEW_TASK : c.intent();
        String goal = (c.taskGoal() == null || c.taskGoal().isBlank()) ? text : c.taskGoal().trim();
        String profile = safeProfile(c);

        boolean needsWeb = c.needsWeb() || signals.needsWeb(text);
        boolean needsFiles = c.needsFiles() || signals.needsFiles(text);
        boolean needsImage = c.needsImageGen();

        if ("IMAGE".equals(profile)) {
            if (needsWeb || needsFiles || c.confidence() < minConfidence
                    || signals.imageIntoDoc(text)) {
                profile = "FULL";
                if (intent == IntentType.IMAGE_GENERATION) intent = IntentType.NEW_TASK;
            }
        }
        if ("QUESTION".equals(profile)
                && (needsWeb || needsFiles || needsImage
                || text.length() > props.getRules().getQuestionMaxLen())) {
            profile = "FULL";
            intent = IntentType.NEW_TASK;
        }

        boolean structured = c.requiresStructuredPlan()
                || signals.complex(text)
                || (needsImage && needsFiles)
                || (needsWeb && needsFiles);

        boolean useHistory = c.shouldUseHistory()
                || intent == IntentType.CONTINUE_TASK
                || intent == IntentType.REVIEW
                || signals.continueTask(text);

        String reason = c.reason() == null || c.reason().isBlank()
                ? "small-model" : ("small-model: " + c.reason());

        return switch (profile) {
            case "QUESTION" -> new TaskPlan(IntentType.QUESTION, goal, true, false, true,
                    copy(props.getToolProfiles().getQuestion()), List.of(), reason, false);
            case "IMAGE" -> new TaskPlan(IntentType.IMAGE_GENERATION, goal, true, useHistory, true,
                    copy(props.getToolProfiles().getImage()), List.of(), reason, false);
            default -> {
                IntentType out = intent == IntentType.QUESTION || intent == IntentType.IMAGE_GENERATION
                        ? IntentType.NEW_TASK : intent;
                if (out == IntentType.UNKNOWN) out = IntentType.NEW_TASK;
                if (hasImage && out == IntentType.NEW_TASK
                        && text.length() <= props.getRules().getReviewMaxLen()) {
                    out = IntentType.REVIEW;
                }
                List<String> tools = props.getToolProfiles().getFull();
                yield new TaskPlan(out, goal, true, true, true,
                        tools == null || tools.isEmpty() ? null : List.copyOf(tools),
                        List.of(), reason, structured);
            }
        };
    }

    private static String safeProfile(LlmIntentClassifier.Classification c) {
        if (c == null || c.toolProfile() == null) return "FULL";
        String p = c.toolProfile().trim().toUpperCase();
        return switch (p) {
            case "QUESTION", "IMAGE", "FULL" -> p;
            default -> "FULL";
        };
    }

    private TaskPlan planByHeuristic(String text, boolean hasImage, List<ChatMessage> recentHistory) {
        boolean complex = signals.complex(text);
        boolean pureImage = signals.pureImage(text);
        boolean intoDoc = signals.imageIntoDoc(text);
        boolean web = signals.needsWeb(text);
        boolean files = signals.needsFiles(text);
        IntentProperties.Rules rules = props.getRules();

        if (rules.isForceFullOnImageIntoDoc() && intoDoc) {
            return ruleGate.full(text, "heuristic:image-into-doc", true);
        }
        if (rules.isForceFullOnWebAndFile() && web && files) {
            return ruleGate.full(text, "heuristic:web+file", true);
        }
        if (pureImage && text.length() <= rules.getPureImageMaxLen()
                && !complex && !web && !files && !intoDoc) {
            return new TaskPlan(IntentType.IMAGE_GENERATION, text, true, true, true,
                    copy(props.getToolProfiles().getImage()), List.of(),
                    "heuristic:pure-image", false);
        }
        if (complex) {
            return ruleGate.full(text, "heuristic:complex", true);
        }
        if (!text.isBlank() && signals.continueTask(text)
                && recentHistory != null && !recentHistory.isEmpty()) {
            List<String> tools = props.getToolProfiles().getFull();
            return new TaskPlan(IntentType.CONTINUE_TASK, text, true, true, true,
                    tools == null || tools.isEmpty() ? null : List.copyOf(tools),
                    List.of(), "heuristic:continue", false);
        }
        if (signals.questionIntent(text)) {
            return new TaskPlan(IntentType.QUESTION, text, true, false, true,
                    copy(props.getToolProfiles().getQuestion()), List.of(),
                    "heuristic:question", false);
        }
        return ruleGate.full(text, "heuristic:default-full", false);
    }

    private static List<String> copy(List<String> tools) {
        return tools == null ? List.of() : new ArrayList<>(tools);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 暴露给测试 */
    public boolean isQuestionIntent(String text) {
        return signals.questionIntent(text);
    }
}
