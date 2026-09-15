package com.miniagent.agent.intent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
    @Autowired
    private IntentConfidenceCalibrator confidenceCalibrator;
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
            // 运行信号匹配，将结果注入 L1 分类器上下文，消除与 L2 的关键词不一致
            String signalContext = buildSignalContext(text);
            LlmIntentClassifier.Classification c =
                    classifier.classify(text, hasImage, recentHistory, chatModel, signalContext);
            if (c != null && c.confidence() >= Math.max(minConfidence,
                    clamp(props.getRejectConfidence()))) {
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
        if (Objects.nonNull(hitLogService)) {
            hitLogService.recordSkip(layer, why);
        }
    }

    private TaskPlan finish(String layer, String userText, TaskPlan plan,
                            LlmIntentClassifier.Classification c, long t0) {
        IntentDecision decision = buildDecision(layer, userText, plan, c);
        TaskPlan enriched = plan == null ? null : plan.withDecision(decision);
        if (enriched != null && decision.needClarification()
                && enriched.intent() != IntentType.QUESTION
                && enriched.intent() != IntentType.REVIEW
                && !clarificationKeepsFullTools(enriched.intent())) {
            String why = clarificationReason(decision);
            decision = decision.withClarification(true, why);
            // 如果原始意图是 NEW_TASK 或 CONTINUE_TASK，保留 FULL 工具（null=全量），
            // 而不是限制为 question 专用工具（仅 skill_list/skill_view/memory）。
            // 这样即使进入澄清模式，agent 仍然可以执行文件操作等任务。
            List<String> clarificationTools;
            if (clarificationKeepsFullTools(enriched.intent())) {
                clarificationTools = enriched.allowedTools(); // null = all tools
            } else {
                clarificationTools = copy(props.getToolProfiles().getQuestion());
            }
            enriched = new TaskPlan(IntentType.QUESTION,
                    "请澄清：" + (enriched.taskGoal().isBlank() ? userText : enriched.taskGoal()),
                    true, true, false,
                    clarificationTools, List.of(), why, false)
                    .withDecision(decision);
        }
        plan = enriched;
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

    private IntentDecision buildDecision(String layer, String userText, TaskPlan plan,
                                         LlmIntentClassifier.Classification c) {
        IntentDecisionSource source = c != null ? c.source()
                : "L0".equalsIgnoreCase(layer) ? IntentDecisionSource.RULE
                : IntentDecisionSource.HEURISTIC;
        double raw = c != null ? c.confidence()
                : "L0".equalsIgnoreCase(layer) ? 0.98 : 0.72;
        double confidence = confidenceCalibrator == null
                ? raw : confidenceCalibrator.calibrate(raw, source);
        List<IntentAlternative> alternatives = c == null ? List.of()
                : c.alternatives().stream()
                .map(a -> new IntentAlternative(a.intent(),
                        confidenceCalibrator == null ? a.confidence()
                                : confidenceCalibrator.calibrate(a.confidence(), source),
                        a.reason()))
                .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                .toList();
        List<String> capabilities = new ArrayList<>();
        if (c != null) {
            capabilities.addAll(c.requiredCapabilities());
        }
        if (plan != null) {
            if (c != null && c.needsWeb()) {
                capabilities.add("web");
            }
            if (c != null && c.needsFiles()) {
                capabilities.add("file");
            }
            if (c != null && c.needsImageGen()) {
                capabilities.add("image");
            }
            if (capabilities.isEmpty() && plan.needsTools()) {
                capabilities.add("general");
            }
        }
        IntentRiskLevel risk = c != null ? c.riskLevel() : inferRisk(plan);
        IntentDecision provisional = new IntentDecision(
                plan == null ? IntentType.UNKNOWN : plan.intent(), confidence,
                alternatives, plan != null && plan.requiresStructuredPlan(), false,
                capabilities, risk, plan == null ? userText : plan.taskGoal(),
                c != null && c.needsWeb(), c != null && c.needsFiles(),
                c != null && c.needsImageGen(), plan != null && plan.shouldUseHistory(),
                c == null ? "FULL" : c.toolProfile(),
                plan == null ? "" : plan.reason(), source);
        boolean ambiguous = provisional.alternativeMargin()
                < Math.max(0.0, props.getAlternativeMargin());
        boolean lowButClarifiable = confidence < clamp(props.getMinConfidence())
                && confidence >= clamp(props.getClarifyConfidence());
        return provisional.withClarification(ambiguous || lowButClarifiable,
                ambiguous ? "候选意图置信度接近，需要澄清" : "意图置信度不足，需要澄清");
    }

    private static IntentRiskLevel inferRisk(TaskPlan plan) {
        if (plan == null || plan.intent() == null) {
            return IntentRiskLevel.MEDIUM;
        }
        return switch (plan.intent()) {
            case PUBLISHING, FILE_DELIVERY -> IntentRiskLevel.HIGH;
            case RESEARCH, NEW_TASK, CONTINUE_TASK, MULTIMODAL_ANALYSIS -> IntentRiskLevel.MEDIUM;
            default -> IntentRiskLevel.LOW;
        };
    }

    private static String clarificationReason(IntentDecision decision) {
        StringBuilder sb = new StringBuilder("意图不够明确");
        if (!decision.alternatives().isEmpty()) {
            sb.append("（候选：");
            for (int i = 0; i < decision.alternatives().size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(decision.alternatives().get(i).intent());
            }
            sb.append("）");
        }
        return sb.append("，请说明你希望查询、修改、生成还是发布什么内容。").toString();
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
        boolean diagram = signals.deliverableDiagram(text);

        if (intent == IntentType.PUBLISHING && !signals.looksLikePublish(text)) {
            intent = needsFiles || diagram ? IntentType.FILE_DELIVERY : IntentType.NEW_TASK;
        }
        if (intent == IntentType.NEW_TASK && signals.inMemoryTask(text)) {
            intent = IntentType.QUESTION;
            profile = "QUESTION";
        }

        if (diagram) {
            profile = "FULL";
            needsFiles = true;
            if (intent != IntentType.CONTINUE_TASK) {
                intent = IntentType.FILE_DELIVERY;
            }
        }
        if (intent == IntentType.FILE_DELIVERY || intent == IntentType.RESEARCH
                || intent == IntentType.PUBLISHING
                || intent == IntentType.MULTIMODAL_ANALYSIS) {
            profile = "FULL";
        }

        if ("IMAGE".equals(profile)) {
            if (needsWeb || needsFiles || c.confidence() < minConfidence
                    || signals.imageIntoDoc(text)) {
                profile = "FULL";
                if (intent == IntentType.IMAGE_GENERATION) {
                    intent = IntentType.FILE_DELIVERY;
                }
            }
        }
        if ("QUESTION".equals(profile)
                && (needsWeb || needsFiles || needsImage
                || text.length() > props.getRules().getQuestionMaxLen())) {
            profile = "FULL";
            intent = IntentType.NEW_TASK;
        }
        // 如果 intent 是 QUESTION 但需要网络或文件，也应该提升为 NEW_TASK
        if (intent == IntentType.QUESTION
                && (needsWeb || needsFiles || needsImage
                || text.length() > props.getRules().getQuestionMaxLen())) {
            intent = needsFiles ? IntentType.FILE_DELIVERY : IntentType.NEW_TASK;
            profile = "FULL";
        }
        if (intent == IntentType.NEW_TASK && needsFiles && !signals.looksLikePublish(text)
                && !needsWeb) {
            intent = IntentType.FILE_DELIVERY;
        }

        boolean simpleFile = signals.simpleFileDelivery(text);
        if (simpleFile) {
            intent = IntentType.FILE_DELIVERY;
            profile = "FULL";
        }
        boolean structured = c.requiresStructuredPlan()
                || signals.complex(text)
                || diagram
                || (intent == IntentType.FILE_DELIVERY && !simpleFile)
                || intent == IntentType.RESEARCH
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
                if (out == IntentType.UNKNOWN) {
                    out = IntentType.NEW_TASK;
                }
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
        if (c == null || c.toolProfile() == null) {
            return "FULL";
        }
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

        if (signals.deliverableDiagram(text)) {
            return ruleGate.fileDelivery(text, "heuristic:diagram-file", true);
        }
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
        if (signals.simpleFileDelivery(text)) {
            return ruleGate.fileDelivery(text, "heuristic:simple-file", false);
        }
        if (signals.questionIntent(text) || signals.inMemoryTask(text)) {
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

    /**
     * 构建信号匹配上下文字符串，注入 L1 分类器提示词。
     * 复用 L2 的 {@link IntentSignalMatcher} 信号定义，消除与 L2 的关键词不一致。
     */
    private String buildSignalContext(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("text_length=").append(text.length());
        if (signals.needsWeb(text)) sb.append("\n  - 命中 web 信号");
        if (signals.needsFiles(text)) sb.append("\n  - 命中 file 信号");
        if (signals.pureImage(text)) sb.append("\n  - 命中 pureImage 信号");
        if (signals.imageIntoDoc(text)) sb.append("\n  - 命中 imageIntoDoc 信号");
        if (signals.continueTask(text)) sb.append("\n  - 命中 continue 信号");
        if (signals.taskAction(text)) sb.append("\n  - 命中 taskAction 信号");
        if (signals.complex(text)) sb.append("\n  - 命中 complex 信号");
        if (signals.questionIntent(text)) sb.append("\n  - 命中 question 信号");
        return sb.toString();
    }

    /**
     * 判断指定意图在澄清模式下是否应保留完整工具集。
     * 直接读取 {@link IntentType#requiresFullTools()} 枚举属性，零配置。
     */
    private boolean clarificationKeepsFullTools(IntentType intent) {
        return intent != null && intent.requiresFullTools();
    }

    /** 暴露给测试 */
    public boolean isQuestionIntent(String text) {
        return signals.questionIntent(text);
    }
}
