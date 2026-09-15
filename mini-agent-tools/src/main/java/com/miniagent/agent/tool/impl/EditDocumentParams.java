package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * edit_document 智能文档编辑工具参数
 * 支持通过自然语言修改已有文档的内容
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EditDocumentParams extends ToolParams {

    @ToolParamSchema(description = "文件路径（支持 .docx/.pptx/.xlsx/.md/.html/.txt）", required = true)
    private String path;

    @ToolParamSchema(description = "修改指令（自然语言描述要修改的内容）", required = true)
    private String instruction;

    @ToolParamSchema(description = "查找的原文（精确匹配模式，可选）")
    private String findText;

    @ToolParamSchema(description = "替换的新文（精确匹配模式，可选）")
    private String replaceText;

    @ToolParamSchema(description = "标题（用于修改文档标题，可选）")
    private String newTitle;

    @ToolParamSchema(description = "内容（用于替换或追加内容，可选）")
    private String newContent;

    @ToolParamSchema(description = "操作类型：replace/append/prepend/insert_after/insert_before/delete（可选，默认智能判断）")
    private String operation;

    /**
     * 获取操作类型，如果没有指定则根据指令智能判断
     */
    public String getOperationOrDefault() {
        if (operation != null && !operation.isEmpty()) {
            return operation.toLowerCase();
        }
        return detectOperationFromInstruction();
    }

    /**
     * 根据指令智能判断操作类型
     */
    private String detectOperationFromInstruction() {
        if (instruction == null) {
            return "replace";
        }
        
        String lower = instruction.toLowerCase();
        
        // 删除操作
        if (lower.contains("删除") || lower.contains("移除") || lower.contains("去掉")) {
            return "delete";
        }
        
        // 追加操作
        if (lower.contains("追加") || lower.contains("添加到末尾") || lower.contains("在最后")) {
            return "append";
        }
        
        // 插入操作
        if (lower.contains("在...之前") || lower.contains("在...前面")) {
            return "insert_before";
        }
        if (lower.contains("在...之后") || lower.contains("在...后面")) {
            return "insert_after";
        }
        
        // 替换操作（默认）
        return "replace";
    }
}