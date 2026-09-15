package com.miniagent.agent.memory.writer;

import com.miniagent.memory.SecurityScanner;
import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.GateDecision;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.writer.MemoryWriteGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 写入闸门 —— 只做代码比模型更可靠的那部分判断。
 *
 * <p><b>分层原则</b>（对齐 Claude Code 的实际做法）：
 * <ul>
 *   <li><b>安全层</b>：必须 100% 确定，不能交给模型 —— 复用既有 {@link SecurityScanner}
 *       （凭据外泄 / prompt 注入 / 隐形 unicode），不另写密钥正则。</li>
 *   <li><b>结构层</b>：判据是形式化的，正则可胜任 —— 内容量、纯状态迁移。</li>
 *   <li><b>语义层</b>：判据依赖上下文，用形式判据去逼近语义判断必然两头不讨好
 *       （据实记忆被误杀 + 换个说法就绕过）。已移出本类，写进
 *       {@code DefaultConsolidationService.llmExtract()} 的提炼提示词，由模型判断。</li>
 * </ul>
 *
 * <p>参照实现：Claude Code 把"不该记什么"写成提示词交给模型，代码侧只保留
 * gitleaks 规则移植的密钥扫描（{@code teamMemorySync/secretScanner.ts}）。
 *
 * <p>设计取向：宁可漏拒，不可误拒。闸门是一票否决，误杀一条真记忆不可恢复，
 * 放行一条噪声还能靠遗忘策略回收。
 */
@Component
public class RuleBasedMemoryWriteGate implements MemoryWriteGate {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedMemoryWriteGate.class);

    /** 去掉事件类型前缀与字段标签后，用于内容量判断 */
    private static final Pattern CONTENT_TAG = Pattern.compile(
        "^\\[[A-Z_]+\\]\\s*|(工具|错误|结果|观察)\\s*[:：]\\s*");

    private static final int MIN_CONTENT_CHARS = 8;

    @Value("${agent.memory.gate.enabled:true}")
    private boolean enabled;

    /** 各规则拒绝计数，供运维观测误拒率（不做持久化，进程内统计）。 */
    private final Map<GateDecision.Rule, AtomicLong> denyCounters = new ConcurrentHashMap<>();

    @Override
    public GateDecision evaluate(AgentEvent event, MemoryEntry candidate) {
        if (!enabled || candidate == null) {
            return GateDecision.pass();
        }

        String content = candidate.getContent() == null ? "" : candidate.getContent();

        // 结构层 G-02：信息量不足
        if (meaningfulLength(content) < MIN_CONTENT_CHARS) {
            return deny(GateDecision.Rule.NO_CONTENT,
                "有效内容不足 " + MIN_CONTENT_CHARS + " 字");
        }

        // 结构层 G-03：纯状态迁移
        if (isRitual(event)) {
            return deny(GateDecision.Rule.RITUAL, "状态迁移事件，无实质载荷");
        }

        // 安全层 G-01：复用既有安全扫描
        GateDecision security = scanSecurity(content, candidate.getSummary());
        if (security != null) {
            return security;
        }

        return GateDecision.pass();
    }

    /**
     * 调用既有安全扫描器。命中时返回拒绝裁决，安全时返回 null。
     * 正文与摘要都要扫：摘要同样会进入系统提示。
     */
    private GateDecision scanSecurity(String content, String summary) {
        String reason = SecurityScanner.scan(content);
        if (reason == null && summary != null && !summary.isBlank()) {
            reason = SecurityScanner.scan(summary);
        }
        if (reason == null) {
            return null;
        }
        return deny(GateDecision.Rule.SECURITY, reason);
    }

    private GateDecision deny(GateDecision.Rule rule, String reason) {
        denyCounters.computeIfAbsent(rule, k -> new AtomicLong()).incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("写入闸门拒绝 [{}] {}", rule.code(), reason);
        }
        return GateDecision.deny(rule, reason);
    }

    /** 去掉结构化标签后的有效字符数。 */
    private int meaningfulLength(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        String stripped = CONTENT_TAG.matcher(content).replaceAll("").trim();
        return stripped.replaceAll("\\s+", "").length();
    }

    /** 纯状态迁移：TASK_START / PLAN_CHANGE 且载荷无实质内容。 */
    private boolean isRitual(AgentEvent event) {
        if (event == null) {
            return false;
        }
        Map<String, Object> payload = event.getPayload();
        boolean emptyPayload = payload == null || payload.isEmpty();
        if (!emptyPayload) {
            return false;
        }
        return event.getEventType() == AgentEvent.EventType.TASK_START
            || event.getEventType() == AgentEvent.EventType.PLAN_CHANGE;
    }

    /** 各规则累计拒绝次数，供日志或运维端点汇总。 */
    public Map<GateDecision.Rule, Long> denyStats() {
        Map<GateDecision.Rule, Long> snapshot = new ConcurrentHashMap<>();
        denyCounters.forEach((rule, counter) -> snapshot.put(rule, counter.get()));
        return snapshot;
    }
}
