package com.miniagent.agent.intent;

/**
 * 用户意图分类。库内/API 仍用英文枚举名；展示用 {@link #getLabel()}。
 */
public enum IntentType {
    QUESTION("问答咨询", "寒暄、能力询问或纯问答，通常不需要复杂工具链"),
    REVIEW("截图点评", "用户附带截图/图片，走点评反馈快路径"),
    NEW_TASK("新任务", "需要动手执行的新任务；分类不确定时的默认兜底"),
    CONTINUE_TASK("继续任务", "延续上一轮未完成工作，应带上历史上下文"),
    RESEARCH("调研检索", "偏信息搜集/调研类任务"),
    FILE_DELIVERY("文件交付", "以产出或交付文件为主的任务"),
    PUBLISHING("发布上线", "发布、上线、对外发布类任务"),
    IMAGE_GENERATION("图像生成", "以文生图/改图为主的任务"),
    MULTIMODAL_ANALYSIS("多模态分析", "需要结合图文等多模态内容分析"),
    HISTORY_REFERENCE("引用历史", "明确引用历史话题/会话内容"),
    UNKNOWN("未知意图", "无法归类；流水线中通常会回落到新任务");

    private final String label;
    private final String description;

    IntentType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** 中文短名（页面展示） */
    public String getLabel() {
        return label;
    }

    /** 中文说明 */
    public String getDescription() {
        return description;
    }

    /** 英文码 → 中文短名；未知码原样返回 */
    public static String labelOf(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        try {
            return IntentType.valueOf(raw.trim().toUpperCase()).getLabel();
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
}
