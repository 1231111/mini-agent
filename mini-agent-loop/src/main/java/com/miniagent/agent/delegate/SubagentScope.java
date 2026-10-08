package com.miniagent.agent.delegate;

import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.tool.BuiltinTools;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;

/**
 * 子代理沙箱：在 {@link RunScope} 上切会话/角色/权限/工作区，关闭时恢复父快照。
 */
public final class SubagentScope implements AutoCloseable {

    private final RunScope.Binding binding;

    private SubagentScope(RunScope.Binding binding) {
        this.binding = binding;
    }

    /**
     * 进入子代理沙箱。
     *
     * <p><b>权限只降不升。</b>子代理的权限模式由父会话与请求模式取"更严的一方"得出：
     * 父在 PLAN（计划未批准）时子代理绝不能拿到能写文件的模式；父在 ASK 时子代理也要 ASK。
     * 此前实现是 {@code childMode = DEFAULT} 起步、只在 {@code inheritAsk && parent==ASK} 时继承，
     * 于是"父会话处于 PLAN / ASK 而调用方传了 false"就静默把子代理放到了比父更宽的模式上 ——
     * 委派成了绕过权限闸门的通道。</p>
     *
     * @param inheritAsk 兼容旧调用点保留；不再影响是否继承父的权限（父更严时一律继承更严者）
     */
    public static SubagentScope enter(String subSessionId, String roleId, boolean inheritAsk) {
        RunScope parent = RunScope.capture();
        PermissionMode childMode = stricter(parent.permissionMode(), PermissionMode.DEFAULT);
        Path subWs = BuiltinTools.prepareSubagentWorkspace(subSessionId);
        String role = StringUtils.isBlank(roleId) ? "" : roleId;
        RunScope child = parent.asSubagent(subSessionId, role, childMode, subWs);
        return new SubagentScope(child.bind());
    }

    /**
     * 取更严的权限模式。排序：PLAN &lt; ASK &lt; DEFAULT &lt; ACCEPT_EDITS
     * （PLAN 只读探索，ASK 危险工具需批准，DEFAULT 正常执行面，ACCEPT_EDITS 免二次询问）。
     * 未知/空值一律按 DEFAULT 处理，保证不会因为"认不出来"而放宽。
     */
    static PermissionMode stricter(PermissionMode parent, PermissionMode fallback) {
        PermissionMode base = fallback == null ? PermissionMode.DEFAULT : fallback;
        if (parent == null) {
            return base;
        }
        return rank(parent) <= rank(base) ? parent : base;
    }

    private static int rank(PermissionMode mode) {
        return switch (mode) {
            case PLAN -> 0;
            case ASK -> 1;
            case DEFAULT -> 2;
            case ACCEPT_EDITS -> 3;
        };
    }

    @Override
    public void close() {
        binding.close();
    }
}
