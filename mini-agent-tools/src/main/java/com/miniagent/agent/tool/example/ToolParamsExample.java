package com.miniagent.agent.tool.example;

import com.miniagent.agent.tool.ToolParams;
import com.miniagent.agent.tool.impl.ReadFileParams;
import com.miniagent.agent.tool.impl.WriteFileParams;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具参数使用示例
 */
@Slf4j
public class ToolParamsExample {

    public static void main(String[] args) {
        System.out.println("=== 工具参数实体类示例 ===\n");

        // 示例1：ReadFileParams
        exampleReadFile();

        // 示例2：WriteFileParams
        exampleWriteFile();

        // 示例3：JSON Schema生成
        exampleSchemaGeneration();

        // 示例4：JSON序列化/反序列化
        exampleJsonSerialization();
    }

    /**
     * 示例1：ReadFileParams使用
     */
    private static void exampleReadFile() {
        System.out.println("1. ReadFileParams示例:");

        // 创建参数对象
        ReadFileParams params = new ReadFileParams();
        params.setPath("src/main/java/Main.java");
        params.setOffset(10);
        params.setLimit(50);

        // 使用默认值方法
        System.out.println("   路径: " + params.getPath());
        System.out.println("   偏移量: " + params.getOffsetOrDefault());
        System.out.println("   限制数量: " + params.getLimitOrDefault());

        // 转换为JSON
        String json = params.toJson();
        System.out.println("   JSON: " + json);

        // 从JSON解析
        ReadFileParams parsed = ToolParams.fromJson(json, ReadFileParams.class);
        System.out.println("   解析后路径: " + parsed.getPath());
        System.out.println();
    }

    /**
     * 示例2：WriteFileParams使用
     */
    private static void exampleWriteFile() {
        System.out.println("2. WriteFileParams示例:");

        // 创建参数对象
        WriteFileParams params = new WriteFileParams();
        params.setPath("output.txt");
        params.setContent("Hello, World!");
        params.setMode("overwrite");

        System.out.println("   路径: " + params.getPath());
        System.out.println("   内容: " + params.getContent());
        System.out.println("   是否追加模式: " + params.isAppendMode());

        // 转换为JSON
        String json = params.toJson();
        System.out.println("   JSON: " + json);
        System.out.println();
    }

    /**
     * 示例3：JSON Schema生成
     */
    private static void exampleSchemaGeneration() {
        System.out.println("3. JSON Schema生成示例:");

        // 生成ReadFileParams的Schema
        var readFileSchema = ToolParams.generateSchema(ReadFileParams.class);
        System.out.println("   ReadFileParams Schema:");
        readFileSchema.forEach((key, value) ->
                System.out.println("     " + key + ": " + value));

        System.out.println();

        // 生成WriteFileParams的Schema
        var writeFileSchema = ToolParams.generateSchema(WriteFileParams.class);
        System.out.println("   WriteFileParams Schema:");
        writeFileSchema.forEach((key, value) ->
                System.out.println("     " + key + ": " + value));
        System.out.println();
    }

    /**
     * 示例4：JSON序列化/反序列化
     */
    private static void exampleJsonSerialization() {
        System.out.println("4. JSON序列化/反序列化示例:");

        // 从JSON字符串创建参数
        String json = "{\"path\":\"test.java\",\"offset\":5,\"limit\":100}";
        ReadFileParams params = ToolParams.fromJson(json, ReadFileParams.class);

        System.out.println("   输入JSON: " + json);
        System.out.println("   解析结果:");
        System.out.println("     path: " + params.getPath());
        System.out.println("     offset: " + params.getOffset());
        System.out.println("     limit: " + params.getLimit());

        // 从Map创建参数
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("path", "example.java");
        map.put("offset", 1);
        map.put("limit", 50);
        ReadFileParams fromMap = ToolParams.fromMap(map, ReadFileParams.class);
        System.out.println("   从Map创建:");
        System.out.println("     path: " + fromMap.getPath());
        System.out.println("     offset: " + fromMap.getOffset());
    }
}