package com.miniagent.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.WriteXlsxParams;
import com.miniagent.common.io.AtomicFiles;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Excel写入工具：支持创建Excel表格
 */
@Slf4j
@Component
public class WriteXlsxTool {

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "write_xlsx",
                "写入 Excel 表格（.xlsx），data 用 JSON 二维数组。\n" +
                "仅在需要【自定义工作表名 sheetName】或【关闭列宽自适应 autoSizeColumns=false】时才用本工具。\n" +
                "其余情况用 write_document（path 给 .xlsx）——它固定使用工作表名 Sheet1、开启列宽自适应，并支持 headers 指定表头行。",
                WriteXlsxParams.class,
                this::handle
        );
    }

    public String handle(WriteXlsxParams params) {
        try {
            DocumentResourceBudget.requireText(params.getHeaders(), "Excel headers JSON");
            DocumentResourceBudget.requireText(params.getData(), "Excel data JSON");
            org.apache.poi.ss.util.WorkbookUtil.validateSheetName(params.getSheetNameOrDefault());
            List<String> headers = params.getHeaders() == null || params.getHeaders().isBlank() ? List.of()
                    : MAPPER.readValue(params.getHeaders(), new TypeReference<List<String>>() {});
            List<List<Object>> data = params.getData() == null || params.getData().isBlank() ? List.of()
                    : MAPPER.readValue(params.getData(), new TypeReference<List<List<Object>>>() {});
            validateWorkbookInput(headers, data);
            // 解析路径
            Path targetPath = resolveXlsxPath(params.getPath());
            
            // 创建Excel工作簿
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            
            // 创建工作表
            XSSFSheet sheet = workbook.createSheet(params.getSheetNameOrDefault());
            
            // 创建表头
            int headerRowIndex = 0;
            if (!headers.isEmpty()) {
                createHeaderRow(sheet, headers, headerRowIndex);
                headerRowIndex = 1;
            }
            
            // 创建数据行
            if (!data.isEmpty()) {
                createDataRows(sheet, data, headerRowIndex);
            }
            
            // 自动调整列宽
            if (params.isAutoSizeColumnsOrDefault()) {
                autoSizeColumns(sheet);
            }
            
            // 保存工作簿
            DocumentResourceBudget.write(targetPath, workbook::write);
            
            long fileSize = targetPath.toFile().length();
            log.info("Excel表格已创建: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"rows\":%d,\"columns\":%d,\"message\":\"Excel表格创建成功\"}",
                    BuiltinTools.jsonEscape(targetPath.toString().replace("\\", "/")),
                    fileSize,
                    sheet.getLastRowNum() + 1,
                    sheet.getRow(0) != null ? sheet.getRow(0).getPhysicalNumberOfCells() : 0
            );
            }
            
        } catch (Exception e) {
            log.error("创建Excel表格失败", e);
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "创建Excel表格失败: " + e.getMessage(), false).legacyText();
        }
    }

    private static void validateWorkbookInput(List<String> headers, List<List<Object>> data) {
        if (headers == null || data == null) throw new IllegalArgumentException("Excel headers/data 必须是数组，不能为 null");
        long rows = (headers.isEmpty() ? 0L : 1L) + data.size();
        if (rows > DocumentResourceBudget.MAX_SPREADSHEET_ROWS)
            throw new IllegalArgumentException("Excel 行数超过上限 " + DocumentResourceBudget.MAX_SPREADSHEET_ROWS);
        if (headers.size() > DocumentResourceBudget.MAX_SPREADSHEET_COLUMNS)
            throw new IllegalArgumentException("Excel 列数超过上限 " + DocumentResourceBudget.MAX_SPREADSHEET_COLUMNS);
        long cells = headers.size(), text = 0;
        for (String header : headers) text += validateCell(header);
        for (List<Object> row : data) {
            DocumentResourceBudget.requireActive();
            if (row == null) throw new IllegalArgumentException("Excel 每一行必须是数组，不能为 null");
            if (row.size() > DocumentResourceBudget.MAX_SPREADSHEET_COLUMNS)
                throw new IllegalArgumentException("Excel 列数超过上限 " + DocumentResourceBudget.MAX_SPREADSHEET_COLUMNS);
            cells += row.size();
            if (cells > DocumentResourceBudget.MAX_SPREADSHEET_CELLS)
                throw new IllegalArgumentException("Excel 单元格数超过上限 " + DocumentResourceBudget.MAX_SPREADSHEET_CELLS);
            for (Object value : row) text += validateCell(value);
            if (text > DocumentResourceBudget.MAX_CONTENT_CHARS)
                throw new IllegalArgumentException("Excel 单元格文本总量超过上限 " + DocumentResourceBudget.MAX_CONTENT_CHARS);
        }
    }

    private static int validateCell(Object value) {
        if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
            throw new IllegalArgumentException("Excel 单元格仅支持字符串、数字、布尔值或 null，不支持嵌套数组/对象");
        String text = value == null ? "" : value.toString();
        if (text.length() > DocumentResourceBudget.MAX_CELL_CHARS)
            throw new IllegalArgumentException("Excel 单元格文本超过上限 " + DocumentResourceBudget.MAX_CELL_CHARS);
        return text.length();
    }

    /**
     * 创建表头行
     */
    private void createHeaderRow(XSSFSheet sheet, List<String> headers, int rowIndex) {
        XSSFRow headerRow = sheet.createRow(rowIndex);
        
        XSSFCellStyle headerStyle = sheet.getWorkbook().createCellStyle();
        XSSFFont headerFont = sheet.getWorkbook().createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        for (int i = 0; i < headers.size(); i++) {
            XSSFCell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            
            // 设置表头样式
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * 创建数据行
     */
    private void createDataRows(XSSFSheet sheet, List<List<Object>> data, int startRowIndex) {
        for (int i = 0; i < data.size(); i++) {
            DocumentResourceBudget.requireActive();
            List<Object> rowData = data.get(i);
            XSSFRow row = sheet.createRow(startRowIndex + i);
            
            for (int j = 0; j < rowData.size(); j++) {
                XSSFCell cell = row.createCell(j);
                Object value = rowData.get(j);
                
                if (value == null) {
                    cell.setCellValue("");
                } else if (value instanceof Number number) {
                    java.math.BigDecimal decimal = new java.math.BigDecimal(number.toString());
                    // Excel stores only 15 significant decimal digits; preserve identifiers exactly as text.
                    if (decimal.precision() > 15 || !Double.isFinite(number.doubleValue())) cell.setCellValue(number.toString());
                    else cell.setCellValue(number.doubleValue());
                } else if (value instanceof Boolean) {
                    cell.setCellValue((Boolean) value);
                } else {
                    cell.setCellValue(value.toString());
                }
            }
        }
    }

    /**
     * 自动调整列宽
     */
    private void autoSizeColumns(XSSFSheet sheet) {
        // 获取最大列数
        int maxColumns = 0;
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            XSSFRow row = sheet.getRow(i);
            if (row != null && row.getPhysicalNumberOfCells() > maxColumns) {
                maxColumns = row.getPhysicalNumberOfCells();
            }
        }
        
        // 调整每列宽度
        for (int i = 0; i < maxColumns; i++) {
            DocumentResourceBudget.requireActive();
            sheet.autoSizeColumn(i);
            
            // 确保最小宽度
            int width = sheet.getColumnWidth(i);
            if (width < 2000) {
                sheet.setColumnWidth(i, 2000);
            }
        }
    }

    /**
     * 解析Excel文件路径
     */
    private Path resolveXlsxPath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}
