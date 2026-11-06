# 工具参数实体类指南

## 概述

本指南介绍如何使用实体类+注解的方式定义工具参数，替代传统的Map方式，提供更好的类型安全和IDE支持。

## 传统方式 vs 新方式

### 传统方式（Map）

```java
registry.register("read_file", "读取文件内容",
    Map.of(
        "path", Map.of("type", "string", "description", "文件路径", "required", true),
        "offset", Map.of("type", "integer", "description", "起始行号（默认1）"),
        "limit", Map.of("type", "integer", "description", "最大行数（默认200）")
    ),
    args -> {
        Map<String, Object> p = parseJson(args);
        String path = (String) p.get("path");
        int offset = p.containsKey("offset") ? ((Number) p.get("offset")).intValue() : 1;
        int limit = p.containsKey("limit") ? ((Number) p.get("limit")).intValue() : 200;
        return readFile(path, offset, limit);
    });
```

### 新方式（实体类）

```java
// 1. 定义参数类
@Data
@EqualsAndHashCode(callSuper = true)
public class ReadFileParams extends ToolParams {
    @ToolParamSchema(description = "文件路径", required = true)
    private String path;

    @ToolParamSchema(description = "起始行号（默认1）")
    private Integer offset;

    @ToolParamSchema(description = "最大行数（默认200）")
    private Integer limit;

    public int getOffsetOrDefault() {
        return offset != null ? offset : 1;
    }

    public int getLimitOrDefault() {
        return limit != null ? limit : 200;
    }
}

// 2. 注册工具
registry.register("read_file", "读取文件内容",
    ReadFileParams.class,
    params -> readFile(params.getPath(), 
                      params.getOffsetOrDefault(), 
                      params.getLimitOrDefault()));
```

## 核心组件

### 1. ToolParamSchema 注解

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParamSchema {
    String description();           // 参数描述
    boolean required() default false; // 是否必填
    String defaultValue() default ""; // 默认值
}
```

### 2. ToolParams 基类

所有参数类必须继承此基类：

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class ToolParams {
    // JSON序列化
    public String toJson() { ... }
    
    // 从JSON解析
    public static <T extends ToolParams> T fromJson(String json, Class<T> clazz) { ... }
    
    // 从Map解析
    public static <T extends ToolParams> T fromMap(Map<String, Object> map, Class<T> clazz) { ... }
    
    // 生成JSON Schema
    public static Map<String, Object> generateSchema(Class<? extends ToolParams> clazz) { ... }
}
```

### 3. ToolRegistry 新增方法

```java
/**
 * 类型安全注册：使用参数实体类定义参数
 */
public <T extends ToolParams> void register(
    String name, 
    String description,
    Class<T> paramsClass,
    java.util.function.Function<T, String> handler)
```

## 优势

### 1. 类型安全
- 编译时检查参数类型
- 避免字符串键拼写错误
- IDE自动补全支持

### 2. 代码清晰
- 参数定义与业务逻辑分离
- 字段注解提供文档
- 方法名自描述

### 3. 可维护性
- 参数结构变更只需修改一处
- 便于单元测试
- 支持版本控制

### 4. IDE支持
- 自动补全字段名
- 重构支持
- 代码导航

## 使用示例

### 示例1：简单参数

```java
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentEnvironmentParams extends ToolParams {
    @ToolParamSchema(description = "是否包含Git仓库信息", defaultValue = "false")
    private Boolean includeGit;
    
    public boolean isIncludeGitOrDefault() {
        return includeGit != null ? includeGit : false;
    }
}
```

### 示例2：必填参数

```java
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteFileParams extends ToolParams {
    @ToolParamSchema(description = "文件路径", required = true)
    private String path;
    
    @ToolParamSchema(description = "文件内容", required = true)
    private String content;
    
    @ToolParamSchema(description = "写入模式")
    private String mode;
}
```

### 示例3：复杂参数

```java
@Data
@EqualsAndHashCode(callSuper = true)
public class SearchParams extends ToolParams {
    @ToolParamSchema(description = "搜索关键词", required = true)
    private String query;
    
    @ToolParamSchema(description = "搜索范围")
    private String scope;
    
    @ToolParamSchema(description = "是否正则表达式", defaultValue = "false")
    private Boolean regex;
    
    @ToolParamSchema(description = "最大结果数", defaultValue = "100")
    private Integer maxResults;
}
```

## 迁移指南

### 步骤1：创建参数类

为每个工具创建对应的参数类，继承`ToolParams`。

### 步骤2：添加注解

为每个字段添加`@ToolParamSchema`注解。

### 步骤3：更新注册方式

将原来的Map参数改为参数类。

### 步骤4：更新处理器

使用参数类的方法访问参数，而不是从Map中获取。

## 注意事项

1. **向后兼容**：原有的Map方式仍然支持
2. **序列化**：确保参数类有正确的getter/setter
3. **默认值**：在字段定义或方法中处理
4. **验证**：可在参数类中添加验证逻辑

## 最佳实践

1. **命名规范**：使用驼峰命名，与JSON字段名一致
2. **文档完整**：每个字段都要有description
3. **默认值处理**：提供默认值方法
4. **不可变性**：考虑使用record类（Java 16+）

## 示例工具

查看以下文件了解完整示例：
- `AgentEnvironmentTool.java` - 环境感知工具
- `AskUserQuestionTool.java` - 用户交互工具
- `ReadFileParams.java` - 读取文件参数
- `WriteFileParams.java` - 写入文件参数