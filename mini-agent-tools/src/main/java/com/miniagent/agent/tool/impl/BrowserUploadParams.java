package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserUploadParams extends ToolParams {
    @ToolParamSchema(description = "browser_form_inspect返回的type=file字段完整ref", required = true)
    private String ref;
    @ToolParamSchema(description = "当前会话工作区或本人已上传附件路径列表；支持workspace/和user-uploads/别名，最多10个/总50MiB。不允许任意本机路径、目录或链接", required = true)
    private List<String> paths;
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID")
    private String sessionId;
}
