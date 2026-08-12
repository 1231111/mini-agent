package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * capability → 候选工具。静态映射 + 注册表描述关键词补强。
 */
@Component
public class ToolCapabilityIndex {

    private static final Map<String, List<String>> STATIC = Map.ofEntries(
            Map.entry("file_write", List.of("write_file", "edit_file")),
            Map.entry("file_read", List.of("read_file", "list_files", "read_package")),
            Map.entry("file", List.of("write_file", "read_file", "edit_file", "list_files")),
            Map.entry("web", List.of("web_search", "web_extract", "http_get")),
            Map.entry("browser", List.of(
                    "browser_navigate", "browser_snapshot", "browser_click", "browser_type")),
            Map.entry("image", List.of(
                    "image_generate", "comfyui_txt2img", "comfyui_img2img", "comfyui_models")),
            Map.entry("code", List.of(
                    "search_code", "codebase_search", "ast_search", "read_package")),
            Map.entry("shell", List.of("exec_command")),
            Map.entry("memory", List.of("memory")),
            Map.entry("todo", List.of("todo")),
            Map.entry("research", List.of("web_search", "web_extract", "http_get", "read_file")),
            Map.entry("deliver", List.of("write_file", "edit_file")),
            Map.entry("plan", List.of("todo")),
            Map.entry("general", List.of("todo", "memory", "web_search", "write_file", "read_file"))
    );

    private final ToolRegistry toolRegistry;
    private final Map<String, Set<String>> index = new LinkedHashMap<>();

    public ToolCapabilityIndex(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void rebuild() {
        index.clear();
        STATIC.forEach((cap, tools) -> index.put(cap, new LinkedHashSet<>(tools)));
        for (ToolSpecification spec : toolRegistry.getSpecifications()) {
            String name = spec.name();
            String desc = (spec.description() == null ? "" : spec.description())
                    .toLowerCase(Locale.ROOT);
            if (desc.contains("文件") || desc.contains("file") || desc.contains("写入"))
                add("file", name);
            if (desc.contains("搜索") || desc.contains("网页") || desc.contains("web"))
                add("web", name);
            if (desc.contains("浏览器") || desc.contains("browser"))
                add("browser", name);
            if (desc.contains("图") || desc.contains("image") || desc.contains("comfy"))
                add("image", name);
            if (desc.contains("代码") || desc.contains("code") || desc.contains("ast"))
                add("code", name);
            if (desc.contains("命令") || desc.contains("exec") || desc.contains("shell"))
                add("shell", name);
        }
    }

    private void add(String cap, String tool) {
        index.computeIfAbsent(cap, k -> new LinkedHashSet<>()).add(tool);
    }

    public List<String> toolsFor(String capability) {
        // 工具可能在本 Bean PostConstruct 之后才 register，查询时再刷一次
        rebuild();
        if (capability == null || capability.isBlank())
            return new ArrayList<>(index.getOrDefault("general", Set.of()));
        String key = capability.trim().toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        if (index.containsKey(key)) out.addAll(index.get(key));
        for (Map.Entry<String, Set<String>> e : index.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key))
                out.addAll(e.getValue());
        }
        if (out.isEmpty()) out.addAll(index.getOrDefault("general", Set.of()));
        Set<String> registered = toolRegistry.getToolNames();
        if (!registered.isEmpty())
            out.removeIf(t -> !registered.contains(t));
        if (out.isEmpty()) out.add("todo");
        return new ArrayList<>(out);
    }
}
