package com.miniagent.agent.intent;

/**
 * 用户意图分类。库内/API 仍用英文枚举名；展示用 {@link #getLabel()}。
 * <p>
 * 每个意图通过 {@link #requiresFullTools()} 自声明是否需要完整工具集，
 * 系统据此自动决定澄清模式下的工具范围，无需额外配置。
 */
public enum IntentType {
    QUESTION("问答咨询", "寒暄、能力询问或纯问答，通常不需要复杂工具链", false),
    REVIEW("截图点评", "用户附带截图/图片，走点评反馈快路径", false),
    NEW_TASK("新任务", "需要动手执行的新任务；分类不确定时的默认兜底", true),
    CONTINUE_TASK("继续任务", "延续上一轮未完成工作，应带上历史上下文", true),
    RESEARCH("调研检索", "偏信息搜集/调研类任务", true),
    FILE_DELIVERY("文件交付", "以产出或交付文件为主的任务", true),
    PUBLISHING("发布上线", "发布、上线、对外发布类任务", true),
    IMAGE_GENERATION("图像生成", "以文生图/改图为主的任务", false),
    MULTIMODAL_ANALYSIS("多模态分析", "需要结合图文等多模态内容分析", true),
    HISTORY_REFERENCE("引用历史", "明确引用历史话题/会话内容", false),
    UNKNOWN("未知意图", "无法归类；流水线中通常会回落到新任务", true);

    private final String label;
    private final String description;
    private final boolean requiresFullTools;

    IntentType(String label, String description, boolean requiresFullTools) {
        this.label = label;
        this.description = description;
        this.requiresFullTools = requiresFullTools;
    }

    /** 中文短名（页面展示） */
    public String getLabel() {
        return label;
    }

    /** 中文说明 */
    public String getDescription() {
        return description;
    }

    /**
     * 该意图是否需要完整工具集。
     * 澄清模式下，返回 true 的意图保留全量工具（null），
     * 返回 false 的意图限制为 question 专用工具。
     * <p>
     * 新增意图时只需在此声明，无需修改任何其他代码或配置。
     */
    public boolean requiresFullTools() {
        return requiresFullTools;
    }

    /** 英文码 → 中文短名；未知码原样返回 */
    public static String labelOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        try {
            return IntentType.valueOf(raw.trim().toUpperCase()).getLabel();
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
}
