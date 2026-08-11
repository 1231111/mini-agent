package com.miniagent.agent.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * L0 高置信规则短路。匹配逻辑固定，信号词/工具面全部来自配置。
 */
@Component
public class IntentRuleGate {

    private final IntentProperties props;
    private final IntentSignalMatcher signals;

    public IntentRuleGate(IntentProperties props, IntentSignalMatcher signals) {
        this.props = props;
        this.signals = signals;
    }

    /**
     * @return 高置信 TaskPlan；未命中返回 null
     */
    public TaskPlan tryShortCircuit(String text, boolean hasImage) {
        if (text == null) text = "";
        String t = text.trim();
        IntentProperties.Rules rules = props.getRules();

        boolean web = signals.needsWeb(t);
        boolean files = signals.needsFiles(t);
        boolean intoDoc = signals.imageIntoDoc(t);

        if ((rules.isForceFullOnWebAndFile() && web && files)
                || (rules.isForceFullOnImageIntoDoc() && intoDoc)) {
            String reason = web && files ? "rule:web+file" : "rule:image-into-doc";
            return full(t, reason, true);
        }

        if (signals.questionIntent(t)) {
            return new TaskPlan(IntentType.QUESTION, t, true, false, true,
                    copyTools(props.getToolProfiles().getQuestion()), List.of(),
                    "rule:question", false);
        }

        boolean pureImage = signals.pureImage(t);
        if (pureImage && t.length() <= rules.getPureImageMaxLen()
                && !web && !files && !intoDoc
                && !signals.complex(t)) {
            return new TaskPlan(IntentType.IMAGE_GENERATION, t, true, true, true,
                    copyTools(props.getToolProfiles().getImage()), List.of(),
                    "rule:pure-image", false);
        }

        return null;
    }

    TaskPlan full(String goal, String reason, boolean structured) {
        // full=null → 注册表全量（含 MCP）；不写死工具名列表
        List<String> tools = props.getToolProfiles().getFull();
        return new TaskPlan(IntentType.NEW_TASK, goal, true, true, true,
                tools == null || tools.isEmpty() ? null : List.copyOf(tools),
                List.of(), reason, structured);
    }

    private static List<String> copyTools(List<String> tools) {
        return tools == null ? List.of() : new ArrayList<>(tools);
    }
}
