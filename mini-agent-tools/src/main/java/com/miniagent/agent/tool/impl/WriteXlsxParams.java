package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_xlsx 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteXlsxParams extends ToolParams {

    @ToolParamSchema(description = "Excel文件路径（.xlsx）", required = true)
    private String path;

    @ToolParamSchema(description = "工作表名称", defaultValue = "Sheet1")
    private String sheetName;

    @ToolParamSchema(description = "表格数据（JSON二维数组格式）", required = true)
    private String data;

    @ToolParamSchema(description = "表头行（JSON数组格式）")
    private String headers;

    @ToolParamSchema(description = "是否自动调整列宽", defaultValue = "true")
    private Boolean autoSizeColumns;

    /**
     * 获取工作表名称，默认"Sheet1"
     */
    public String getSheetNameOrDefault() {
        return sheetName != null && !sheetName.isEmpty() ? sheetName : "Sheet1";
    }

    /**
     * 是否自动调整列宽，默认true
     */
    public boolean isAutoSizeColumnsOrDefault() {
        return autoSizeColumns != null ? autoSizeColumns : true;
    }
}