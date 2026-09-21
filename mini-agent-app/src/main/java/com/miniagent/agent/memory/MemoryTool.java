package com.miniagent.agent.memory;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.memory.MemoryKeys;
import com.miniagent.memory.MemoryService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;
import org.apache.commons.lang3.StringUtils;

/**
 * 记忆工具：写入走 {@link MemoryService}，按分类落到事实或非结构化笔记。
 */
@Component
public class MemoryTool {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private ToolRegistry toolRegistry;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        registerMemoryTool();
    }

    private void registerMemoryTool() {
        Map<String, Object> actionParam = Map.of(
                "type", "string",
                "description", "操作类型：add=添加，replace=替换，remove=删除，read=读取",
                "required", true
        );
        Map<String, Object> targetParam = Map.of(
                "type", "string",
                "description", "目标：memory=Agent笔记，user=用户画像",
                "required", false
        );
        Map<String, Object> contentParam = Map.of(
                "type", "string",
                "description", "add/replace 时使用：条目内容",
                "required", false
        );
        Map<String, Object> oldTextParam = Map.of(
                "type", "string",
                "description", "replace/remove 时使用：匹配片段（子串匹配）",
                "required", false
        );

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("action", actionParam);
        parameters.put("target", targetParam);
        parameters.put("content", contentParam);
        parameters.put("old_text", oldTextParam);

        toolRegistry.register(Tool.builder()
                .name("memory")
                .description("""
                        持久化记忆工具。保存跨会话的事实：用户偏好、环境配置、工具怪癖和稳定约定。
                        记忆按本轮意图注入系统提示，因此保持紧凑，只聚焦于将来仍然有用的要点。

                        何时保存（主动执行，不要等用户要求）：
                        - 用户纠正你或说"记住这个""不再这样做"
                        - 用户分享偏好、习惯或个人细节
                        - 你发现环境信息（操作系统、已安装工具、项目结构）
                        - 你学到特定约定、API 怪癖或工作流
                        - 你识别出将来仍会用到的稳定事实

                        不要保存：任务进度、会话结果、已完成工作的日志、临时 TODO。
                        """)
                .parameters(parameters)
                .handler(json -> {
                    try {
                        Map<String, Object> args = MAPPER.readValue(json, new TypeReference<>() {});
                        String action = str(args, "action");
                        String target = str(args, "target", MemoryKeys.TARGET_MEMORY);
                        String content = str(args, "content");
                        String oldText = str(args, "old_text");

                        return switch (action) {
                            case "add" -> {
                                if (StringUtils.isBlank(content)) {
                                    yield error("add 需要 content 参数。");
                                }
                                yield MAPPER.writeValueAsString(memoryService.add(target, content));
                            }
                            case "replace" -> {
                                if (StringUtils.isBlank(oldText)) {
                                    yield error("replace 需要 old_text 参数。");
                                }
                                if (StringUtils.isBlank(content)) {
                                    yield error("replace 需要 content 参数。");
                                }
                                yield MAPPER.writeValueAsString(
                                        memoryService.replace(target, oldText, content));
                            }
                            case "remove" -> {
                                if (StringUtils.isBlank(oldText)) {
                                    yield error("remove 需要 old_text 参数。");
                                }
                                yield MAPPER.writeValueAsString(memoryService.remove(target, oldText));
                            }
                            case "read" -> {
                                List<String> entries = memoryService.read(target);
                                yield MAPPER.writeValueAsString(Map.of(
                                        "success", true,
                                        "target", target,
                                        "entries", entries,
                                        "entry_count", entries.size()
                                ));
                            }
                            default -> error("未知操作 '" + action + "'。使用：add, replace, remove, read");
                        };
                    } catch (Exception e) {
                        return error("记忆操作失败: " + e.getMessage());
                    }
                })
                .build());
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return Objects.isNull(v) ? null : v.toString();
    }

    private static String str(Map<String, Object> args, String key, String defaultVal) {
        Object v = args.get(key);
        return Objects.isNull(v) ? defaultVal : v.toString();
    }

    private static String error(String msg) {
        try {
            return MAPPER.writeValueAsString(Map.of("success", false, "error", msg));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
