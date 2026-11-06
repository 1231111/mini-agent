package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_file 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteFileParams extends ToolParams {

    @ToolParamSchema(description = "文件路径", required = true)
    private String path;

    @ToolParamSchema(description = "文件内容（本次要写入/追加的片段）", required = true)
    private String content;

    @ToolParamSchema(description = "写入模式：overwrite=覆盖（默认）；append=追加")
    private String mode;

    /**
     * 显式覆盖许可。
     *
     * <p>overwrite 模式下，工具会核对「目标文件当前内容」是否等于「本会话读到的内容」。
     * 文件没读过、或读过之后被改过，会直接拒绝（{@code CONFLICT}）——
     * 因为盲目写回旧内容会静默回滚别人的改动。</p>
     *
     * <p>只有在用户明确要求「用这份内容整体替换」时才传 {@code true}。
     * 模型不应该为了绕过拦住而习惯性带上它，那等于把这道闸门关掉。</p>
     */
    @ToolParamSchema(description = "仅当用户明确要求整体覆盖一个未读或已变更的文件时传 true；"
            + "默认 false 会被一致性校验拦下")
    private boolean force;

    /**
     * 是否为追加模式
     */
    public boolean isAppendMode() {
        return "append".equalsIgnoreCase(mode);
    }
}