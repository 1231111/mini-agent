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

    public static SubagentScope enter(String subSessionId, String roleId, boolean inheritAsk) {
        RunScope parent = RunScope.capture();
        PermissionMode parentMode = parent.permissionMode();
        PermissionMode childMode = PermissionMode.DEFAULT;
        if (inheritAsk && parentMode == PermissionMode.ASK) {
            childMode = PermissionMode.ASK;
        } else if (parentMode == PermissionMode.ACCEPT_EDITS) {
            childMode = PermissionMode.ACCEPT_EDITS;
        }
        Path subWs = BuiltinTools.prepareSubagentWorkspace(subSessionId);
        String role = StringUtils.isBlank(roleId) ? "" : roleId;
        RunScope child = parent.asSubagent(subSessionId, role, childMode, subWs);
        return new SubagentScope(child.bind());
    }

    @Override
    public void close() {
        binding.close();
    }
}
