package com.miniagent.memory.model;

/**
 * 写入闸门的裁决结果。
 *
 * <p>闸门是**一票否决**：{@code allowed=false} 时调用方必须直接丢弃该候选记忆，
 * 不得降级为"低重要度写入"——降级写入等于把闸门的判断重新交回给打分器，
 * 而打分器正是通不过的那一环。
 *
 * <p>规则集刻意保持极小，只覆盖两类**代码比模型更可靠**的判据：
 * <ul>
 *   <li>安全类：凭据外泄、prompt 注入、隐形字符 —— 必须确定性拦截，不能交给模型。</li>
 *   <li>结构类：内容量、纯状态迁移 —— 判据是形式化的，无需理解语义。</li>
 * </ul>
 * 语义类判断（可推导内容 / git 历史 / 调试配方 / 临时要求）**不在本枚举内**，
 * 它们由记忆提炼提示词交给模型处理。
 */
public class GateDecision {

    /** 闸门规则编号。保留编号是为了让拒绝日志可归因、可统计。 */
    public enum Rule {
        /** 通过 */
        PASS("G-00", "通过"),
        /** 安全扫描拦截：凭据外泄 / prompt 注入 / 隐形 unicode */
        SECURITY("G-01", "安全扫描拦截"),
        /** 内容过短，信息量不足 */
        NO_CONTENT("G-02", "信息量不足"),
        /** 纯状态迁移事件，无可保留信息 */
        RITUAL("G-03", "纯状态迁移");

        private final String code;
        private final String label;

        Rule(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() { return code; }

        public String label() { return label; }
    }

    private final boolean allowed;
    private final Rule rule;
    private final String reason;

    private GateDecision(boolean allowed, Rule rule, String reason) {
        this.allowed = allowed;
        this.rule = rule;
        this.reason = reason;
    }

    public static GateDecision pass() {
        return new GateDecision(true, Rule.PASS, null);
    }

    public static GateDecision deny(Rule rule, String reason) {
        return new GateDecision(false, rule, reason);
    }

    public boolean isAllowed() { return allowed; }

    public Rule getRule() { return rule; }

    public String getReason() { return reason; }

    /** 便于日志归因：G-01 安全扫描拦截（...） */
    public String describe() {
        if (allowed) {
            return "PASS";
        }
        return rule.code() + " " + rule.label() + (reason == null ? "" : "（" + reason + "）");
    }

    @Override
    public String toString() {
        return describe();
    }
}
