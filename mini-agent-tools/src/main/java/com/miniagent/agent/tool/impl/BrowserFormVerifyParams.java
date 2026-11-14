package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data @EqualsAndHashCode(callSuper = true)
public class BrowserFormVerifyParams extends ToolParams {
    @ToolParamSchema(description = "最新browser_form_inspect返回的字段ref", required = true)
    private String ref;
    @ToolParamSchema(description = "期望的实际文本值；四种expected参数最多传一种；省略全部仅回读并检查字段有效性")
    private String expectedValue;
    @ToolParamSchema(description = "期望勾选状态")
    private Boolean expectedChecked;
    @ToolParamSchema(description = "期望选中的原生select或受支持ARIA下拉的选项value数组，顺序不影响核验")
    private List<String> expectedValues;
    @ToolParamSchema(description = "期望的上传文件名数组，只传文件名而非本地路径")
    private List<String> expectedFiles;
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID")
    private String sessionId;
}
