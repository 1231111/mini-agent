package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code exec_command} 策略的<b>唯一裁决点</b>：全局默认（配置）+ 会话覆盖（运行期可改）。
 *
 * <p>为什么要有这个类，而不是继续让 {@code ToolPipeline} 构造器注入一个 boolean：
 * 那样是<b>启动时固定</b>的，出厂要放开、用户又要能随时收紧，两者不可能同时满足。
 *
 * <p>分成两层而不是一层：
 * <ul>
 *   <li><b>全局默认</b>来自 {@code agent.tools.exec-policy}，决定"新会话初始是什么档"。</li>
 *   <li><b>会话覆盖</b>存在 {@link SessionPermissionStore}，决定"这个会话现在是什么档"。</li>
 * </ul>
 * 覆盖为空时跟随全局 —— 所以改了全局配置，没有显式覆盖过的会话会立刻跟着变。
 */
@Slf4j
@Component
public class ExecPolicyService {

    /**
     * 配置里两个键都没给时的兜底。
     *
     * <p>刻意与旧代码 {@code @Value("${agent.tools.exec-enabled:true}")} 的语义一致（即 ALLOW），
     * 否则"没写配置"的档位会悄悄变严，那些没显式配过的环境（含本地开发）行为会变。
     */
    private static final ExecPolicy FALLBACK = ExecPolicy.ALLOW;

    private final ExecPolicy globalDefault;
    private final SessionPermissionStore store;

    public ExecPolicyService(
            @Value("${agent.tools.exec-policy:}") String policy,
            @Value("${agent.tools.exec-enabled:}") String legacyExecEnabled,
            SessionPermissionStore store) {
        this.store = store;
        this.globalDefault = resolveGlobal(policy, legacyExecEnabled);
        log.info("exec_command 策略: 全局默认 = {}（{}）{}",
                globalDefault.wireName(), globalDefault.labelZh(),
                StringUtils.isBlank(policy) && StringUtils.isNotBlank(legacyExecEnabled)
                        ? "  ← 来自已弃用的 agent.tools.exec-enabled，建议改用 agent.tools.exec-policy"
                        : "");
    }

    static ExecPolicy resolveGlobal(String policy, String legacyExecEnabled) {
        ExecPolicy parsed = ExecPolicy.parse(policy);
        if (parsed != null) {
            if (StringUtils.isNotBlank(legacyExecEnabled)) {
                log.warn("agent.tools.exec-policy 与已弃用的 agent.tools.exec-enabled 同时出现，"
                        + "以前者为准: exec-policy={} exec-enabled={}", policy, legacyExecEnabled);
            }
            return parsed;
        }
        if (StringUtils.isNotBlank(policy)) {
            // 刻意抛而不是兜底：静默降级会让"我明明配了 block，怎么还在执行命令"极难查。
            throw new IllegalStateException("agent.tools.exec-policy 取值无法识别: '" + policy
                    + "'（可选 block / ask / allow）");
        }
        ExecPolicy fromLegacy = ExecPolicy.fromLegacyExecEnabled(legacyExecEnabled);
        if (fromLegacy != null) {
            return fromLegacy;
        }
        if (StringUtils.isNotBlank(legacyExecEnabled)) {
            throw new IllegalStateException("agent.tools.exec-enabled 取值无法识别: '"
                    + legacyExecEnabled + "'（只接受 true / false；新配置请用 agent.tools.exec-policy）");
        }
        return FALLBACK;
    }

    public ExecPolicy globalDefault() {
        return globalDefault;
    }

    /** 不含会话模式提升的生效策略：会话覆盖优先，否则全局默认。 */
    public ExecPolicy effective(String sessionId) {
        ExecPolicy override = store.getExecPolicyOverride(sessionId);
        return override != null ? override : globalDefault;
    }

    /**
     * 最终生效策略（已计入 {@link PermissionMode}）。
     * 判定链路上用这个，不要用 {@link #effective(String)} —— 否则 ACCEPT_EDITS 的提升会失效。
     */
    public ExecPolicy effective(String sessionId, PermissionMode mode) {
        return PermissionPolicy.effectiveExecPolicy(mode, effective(sessionId));
    }
}
