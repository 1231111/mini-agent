package com.miniagent.agent.tool;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * ast_search：基于 JavaParser 的结构化代码检索。
 *
 * 与 search_code（文本正则）的区别：理解 Java 语法结构，能精确按
 * 「方法定义 / 类定义 / 字段定义 / 方法调用 / 注解」查找，不被注释、字符串、
 * 同名子串干扰。适合「找 foo() 在哪定义」「谁调用了 bar()」「带 @Transactional 的方法」这类查询。
 */
@Slf4j
@Component
public class AstSearchTool {

    @Autowired
    private ToolRegistry toolRegistry;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("ast_search")
                .description("""
                        Java 源码结构化检索（基于 AST，比文本搜索精确，不受注释/字符串干扰）。
                        query_type 取值：
                          - method_def：找方法定义（name=方法名，可省略列全部）
                          - class_def：找类/接口定义（name=类名）
                          - field_def：找字段定义（name=字段名）
                          - method_call：找方法调用点（name=被调用方法名）
                          - annotated：找带某注解的方法/类（name=注解名，不带@）
                        返回 文件:行号:签名/上下文。path 默认项目根目录，只扫 .java 文件。
                        定位「在哪定义、谁调用、带什么注解」用这个；纯文本/非 Java 用 search_code。
                        """)
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query_type", Map.of("type", "string",
                "description", "method_def | class_def | field_def | method_call | annotated", "required", true));
        params.put("name", Map.of("type", "string",
                "description", "目标名字（方法名/类名/字段名/注解名）。method_def 可省略以列全部方法"));
        params.put("path", Map.of("type", "string",
                "description", "搜索目录，默认项目根目录"));
        return params;
    }

    private String handle(String json) {
        try {
            Map<String, Object> args = MAPPER.readValue(Optional.ofNullable(json).orElse("{}"), Map.class);
            String queryType = String.valueOf(args.getOrDefault("query_type", "")).trim();
            if (queryType.isEmpty()) return err("query_type 不能为空");
            String name = Objects.isNull(args.get("name")) ? null : String.valueOf(args.get("name")).trim();
            String pathArg = Objects.isNull(args.get("path")) ? null : String.valueOf(args.get("path")).trim();

            Path root = (StringUtils.isBlank(pathArg))
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                    : resolvePath(pathArg);
            if (!Files.exists(root)) return err("路径不存在: " + pathArg);

            List<Path> javaFiles;
            try (var stream = Files.walk(root)) {
                javaFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> { String s = p.toString().replace('\\', '/');
                                       return !s.contains("/target/") && !s.contains("/build/"); })
                        .toList();
            }

            List<String> hits = new ArrayList<>();
            for (Path f : javaFiles) {
                if (hits.size() >= MAX_RESULTS) break;
                scanFile(f, root, queryType, name, hits);
            }

            if (hits.isEmpty()) return "（无匹配）query_type=" + queryType + (Objects.nonNull(name) ? ", name=" + name : "");
            StringBuilder sb = new StringBuilder("匹配 " + hits.size() + " 处"
                    + (hits.size() >= MAX_RESULTS ? "（已达上限）" : "") + ":\n");
            hits.forEach(h -> sb.append(h).append('\n'));
            return sb.toString();
        } catch (Exception e) {
            log.error("ast_search 失败", e);
            return err("ast_search 执行失败: " + e.getMessage());
        }
    }

    private void scanFile(Path file, Path root, String queryType, String name, List<String> hits) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return; // 解析失败（语法错误/非标准源码）跳过
        }
        String rel = root.relativize(file).toString().replace('\\', '/');

        switch (queryType) {
            case "method_def" -> cu.findAll(MethodDeclaration.class).forEach(m -> {
                if (matches(m.getNameAsString(), name)) {
                    add(hits, rel, line(m), m.getDeclarationAsString(false, false, false));
                }
            });
            case "class_def" -> cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                if (matches(c.getNameAsString(), name)) {
                    String kind = c.isInterface() ? "interface " : "class ";
                    add(hits, rel, line(c), kind + c.getNameAsString());
                }
            });
            case "field_def" -> cu.findAll(FieldDeclaration.class).forEach(fd ->
                fd.getVariables().forEach(v -> {
                    if (matches(v.getNameAsString(), name)) {
                        add(hits, rel, line(fd), fd.getElementType() + " " + v.getNameAsString());
                    }
                }));
            case "method_call" -> cu.findAll(MethodCallExpr.class).forEach(mc -> {
                if (matches(mc.getNameAsString(), name)) {
                    add(hits, rel, line(mc), trunc(mc.toString()));
                }
            });
            case "annotated" -> {
                cu.findAll(MethodDeclaration.class).forEach(m -> {
                    if (hasAnnotation(m.getAnnotations(), name)) {
                        add(hits, rel, line(m), "@" + name + " " + m.getDeclarationAsString(false, false, false));
                    }
                });
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                    if (hasAnnotation(c.getAnnotations(), name)) {
                        add(hits, rel, line(c), "@" + name + " class " + c.getNameAsString());
                    }
                });
            }
            default -> { /* 未知 query_type：交由 handle 校验，这里不产出 */ }
        }
    }

    private static boolean matches(String actual, String wanted) {
        return StringUtils.isBlank(wanted) || actual.equals(wanted);
    }

    private static boolean hasAnnotation(com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.AnnotationExpr> anns, String name) {
        if (Objects.isNull(name)) return !anns.isEmpty();
        return anns.stream().anyMatch(a -> a.getNameAsString().equals(name));
    }

    private static int line(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static void add(List<String> hits, String rel, int line, String sig) {
        if (hits.size() < MAX_RESULTS) hits.add(rel + ":" + line + ":" + trunc(sig));
    }

    private static String trunc(String s) {
        s = s.replace('\n', ' ').strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path.replace('\\', '/').trim());
        if (p.isAbsolute()) return p.normalize();
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve(p).normalize();
    }

    private String err(String msg) {
        try { return MAPPER.writeValueAsString(Map.of("success", false, "error", msg)); }
        catch (Exception e) { return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}"; }
    }
}
