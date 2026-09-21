package com.miniagent.agent.planner;

import java.util.List;
import java.util.ArrayList;

/**
 * PlanValidationReport - 结构化验证报告
 * 包含详细的错误信息、节点关联和错误类型，支持 replan 闭环
 */
public record PlanValidationReport(List<ValidationError> errors, List<ValidationWarning> warnings) {
    public PlanValidationReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    // 向后兼容的构造函数
    public PlanValidationReport(List<String> errorStrings, List<String> warningStrings, boolean dummy) {
        this(
            errorStrings != null ? errorStrings.stream()
                .map(s -> new ValidationError(ValidationError.Type.GENERAL, null, s, null))
                .toList() : List.of(),
            warningStrings != null ? warningStrings.stream()
                .map(s -> new ValidationWarning(ValidationWarning.Type.GENERAL, null, s))
                .toList() : List.of()
        );
    }

    public boolean valid() { return errors.isEmpty(); }
    public String summary() {
        return "errors=" + errors.stream().map(ValidationError::summary).toList() +
               ", warnings=" + warnings.stream().map(ValidationWarning::summary).toList();
    }

    /**
     * 获取所有错误消息（向后兼容）
     */
    public List<String> errorMessages() {
        return errors.stream().map(ValidationError::message).toList();
    }

    /**
     * 获取所有警告消息（向后兼容）
     */
    public List<String> warningMessages() {
        return warnings.stream().map(ValidationWarning::message).toList();
    }

    /**
     * 生成用于 replan 的修正提示
     */
    public String toCorrectionPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("计划验证失败，需要修正：\n\n");
        for (ValidationError error : errors) {
            sb.append("- 错误类型: ").append(error.type()).append("\n");
            if (error.nodeId() != null) {
                sb.append("  关联节点: ").append(error.nodeId()).append("\n");
            }
            sb.append("  描述: ").append(error.message()).append("\n");
            if (error.suggestion() != null) {
                sb.append("  建议: ").append(error.suggestion()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取特定类型的错误数量
     */
    public long countByType(ValidationError.Type type) {
        return errors.stream().filter(e -> e.type() == type).count();
    }

    /**
     * 是否包含致命错误（导致图结构无效）
     */
    public boolean hasFatalErrors() {
        return errors.stream().anyMatch(e -> e.type().isFatal());
    }

    /**
     * 结构化验证错误
     */
    public record ValidationError(Type type, String nodeId, String message, String suggestion) {
        public enum Type {
            EMPTY_GRAPH("图为空", true),
            HAS_CYCLE("图包含循环", true),
            DUPLICATE_IDS("节点ID重复", true),
            INVALID_DONE_WHEN("完成条件无效", true),
            MISSING_DEPENDENCY("依赖不存在", true),
            UNPRODUCED_INPUTS("输入未被前置节点产出", true),
            BLANK_NODE_ID("节点ID为空", true),
            BLANK_NODE_NAME("节点名称为空", true),
            UNDER_DECOMPOSED("拆解不足", true),
            GENERAL("通用错误", true),
            TOOL_SURFACE("工具表面问题", false),
            CAPABILITY_MISMATCH("能力不匹配", true),
            RESOURCE_UNAVAILABLE("资源不可用", false);

            private final String description;
            private final boolean fatal;

            Type(String description, boolean fatal) {
                this.description = description;
                this.fatal = fatal;
            }

            public String getDescription() { return description; }
            public boolean isFatal() { return fatal; }
        }

        public String summary() {
            return "[" + type + "]" + (nodeId != null ? "@" + nodeId : "") + ": " + message;
        }
    }

    /**
     * 结构化验证警告
     */
    public record ValidationWarning(Type type, String nodeId, String message) {
        public enum Type {
            PERFORMANCE("性能问题"),
            COMPATIBILITY("兼容性问题"),
            BEST_PRACTICE("最佳实践建议"),
            GENERAL("通用警告");

            private final String description;

            Type(String description) {
                this.description = description;
            }

            public String getDescription() { return description; }
        }

        public String summary() {
            return "[" + type + "]" + (nodeId != null ? "@" + nodeId : "") + ": " + message;
        }
    }
}
