package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code agent.tools.*} 配置的绑定与校验：全局并发额度 + 逐工具外层闸门覆盖。
 *
 * <h2>timeout-overrides 的语义（两条规则）</h2>
 * <ol>
 *   <li><b>值 = 该工具的外层闸门秒数</b>（{@code AgentLoop} 里 {@code future.get} 的强杀点）：
 *       写 300 就是 300s，所见即所得。</li>
 *   <li>「外层闸门 = 内层预算 + 余量」不变式机械保持：内层预算同步改为
 *       {@code 值 − 该档位余量}（余量 0 的工具两者相等；{@code song_generate} 余量 30s、
 *       {@code exec_command} 余量 15s、未声明契约的兜底档余量 15s）。
 *       {@code 值 ≤ 余量} 的条目推不出正的内层预算，视为非法并忽略。</li>
 * </ol>
 *
 * <h2>优先级与例外</h2>
 *
 * <p>优先级：{@code exec_command} 的 {@code timeout} 参数（调用方按次声明）&gt;
 * 这里的覆盖 &gt; 注册契约（含注册期从配置派生的预算）。</p>
 *
 * <p>{@code exec_command} <b>不接受</b>名字级覆盖：它的预算随每次调用的命令变化
 * （{@code git status} 与 {@code mvnw package} 差三个数量级），写死一个数必然是谎言。
 * 配了也只会在启动自检里告警并忽略。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.tools")
public class AgentToolsProperties {

    /** 全局工具并发额度（同时执行的工具调用数上限，公平信号量）。 */
    private int maxConcurrency = 8;

    /** 工具名 → 外层闸门秒数。键就是工具名原样（含 {@code mcp__} 前缀名）。 */
    private Map<String, Long> timeoutOverrides = new LinkedHashMap<>();

    public AgentToolsProperties() {
    }

    /** 测试用便捷构造：只定并发额度，无 timeout-overrides。 */
    public AgentToolsProperties(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Map<String, Long> getTimeoutOverrides() {
        return timeoutOverrides;
    }

    public void setTimeoutOverrides(Map<String, Long> timeoutOverrides) {
        this.timeoutOverrides = timeoutOverrides == null ? new LinkedHashMap<>() : new LinkedHashMap<>(timeoutOverrides);
    }

    /**
     * 该工具的有效外层闸门覆盖；没有覆盖、或条目非法时返回 {@code null}（回退注册契约）。
     *
     * <p>非法 = 值非正、或 {@code exec_command}（预算随调用参数变化，见类注释）。
     * 「值 ≤ 档位余量」的校验需要知道档位余量，放在使用侧
     * （{@code ToolExecutionGuards}）判定，那里能拿到该工具的执行契约。</p>
     */
    public Long gateOverrideSeconds(String toolName) {
        if (toolName == null || timeoutOverrides.isEmpty()) {
            return null;
        }
        Long value = timeoutOverrides.get(toolName);
        if (value == null || value <= 0 || ToolConcurrencyPolicy.EXEC_TOOL.equals(toolName)) {
            return null;
        }
        return value;
    }
}
