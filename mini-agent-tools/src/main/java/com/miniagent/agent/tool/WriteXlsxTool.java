package com.miniagent.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.WriteXlsxParams;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            // 解析路径
            Path targetPath = resolveXlsxPath(params.getPath());
            
            // 创建Excel工作簿
            XSSFWorkbook workbook = new XSSFWorkbook();
            
            // 创建工作表
            XSSFSheet sheet = workbook.createSheet(params.getSheetNameOrDefault());
            
            // 创建表头
            String headersJson = params.getHeaders();
            int headerRowIndex = 0;
            if (headersJson != null && !headersJson.isEmpty()) {
                List<String> headers = MAPPER.readValue(
                        headersJson, new TypeReference<List<String>>() {});
                createHeaderRow(sheet, headers, headerRowIndex);
                headerRowIndex = 1;
            }
            
            // 创建数据行
            String dataJson = params.getData();
            if (dataJson != null && !dataJson.isEmpty()) {
                List<List<Object>> data = MAPPER.readValue(
                        dataJson, new TypeReference<List<List<Object>>>() {});
                createDataRows(sheet, data, headerRowIndex);
            }
            
            // 自动调整列宽
            if (params.isAutoSizeColumnsOrDefault()) {
                autoSizeColumns(sheet);
            }
            
            // 保存工作簿
            try (FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
                workbook.write(out);
            }
            
            workbook.close();
            
            long fileSize = targetPath.toFile().length();
            log.info("Excel表格已创建: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"rows\":%d,\"columns\":%d,\"message\":\"Excel表格创建成功\"}",
                    targetPath.toString().replace("\\", "/"),
                    fileSize,
                    sheet.getLastRowNum() + 1,
                    sheet.getRow(0) != null ? sheet.getRow(0).getPhysicalNumberOfCells() : 0
            );
            
        } catch (Exception e) {
            log.error("创建Excel表格失败", e);
            return "{\"success\":false,\"error\":\"创建Excel表格失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 创建表头行
     */
    private void createHeaderRow(XSSFSheet sheet, List<String> headers, int rowIndex) {
        XSSFRow headerRow = sheet.createRow(rowIndex);
        
        for (int i = 0; i < headers.size(); i++) {
            XSSFCell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            
            // 设置表头样式
            XSSFCellStyle headerStyle = sheet.getWorkbook().createCellStyle();
            XSSFFont headerFont = sheet.getWorkbook().createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * 创建数据行
     */
    private void createDataRows(XSSFSheet sheet, List<List<Object>> data, int startRowIndex) {
        for (int i = 0; i < data.size(); i++) {
            List<Object> rowData = data.get(i);
            XSSFRow row = sheet.createRow(startRowIndex + i);
            
            for (int j = 0; j < rowData.size(); j++) {
                XSSFCell cell = row.createCell(j);
                Object value = rowData.get(j);
                
                if (value == null) {
                    cell.setCellValue("");
                } else if (value instanceof Number) {
                    cell.setCellValue(((Number) value).doubleValue());
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