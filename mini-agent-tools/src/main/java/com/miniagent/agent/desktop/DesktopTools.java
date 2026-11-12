package com.miniagent.agent.desktop;

import com.miniagent.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class DesktopTools {
    private final ToolRegistry registry;
    private final DesktopInteractionService desktop;
    public DesktopTools(ToolRegistry registry, DesktopInteractionService desktop) { this.registry = registry; this.desktop = desktop; }
    @PostConstruct public void register() {
        registry.registerStructured("desktop_observe", "观察本机Windows前台窗口，返回截图、窗口列表和一次性observationId。"
                + "网页优先browser工具；桌面控件当前采用视觉定位。不会执行Shell、安装程序或自动接受系统权限。",
                Map.of(), ignored -> desktop.observe());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("observationId", Map.of("type", "string", "required", true, "description", "最近一次当前任务桌面观察ID，动作后失效"));
        params.put("action", Map.of("type", "string", "required", true, "enum", java.util.List.of("click", "double_click", "right_click", "type", "key", "scroll", "focus"),
                "description", "单一动作click/double_click/right_click/type/key/scroll/focus；每次动作都经过权限检查"));
        params.put("x", Map.of("type", "integer", "description", "点击或滚动X：截图左上角为原点的物理像素；scroll不传坐标时使用窗口中心"));
        params.put("y", Map.of("type", "integer", "description", "点击或滚动Y：必须和X一起提供"));
        params.put("text", Map.of("type", "string", "description", "type输入文字，最多2048字符；换行和Tab使用单独key动作，不替换剪贴板"));
        params.put("key", Map.of("type", "string", "description", "key组合如CTRL+A、ENTER、TAB、ESC，最多4键"));
        params.put("amount", Map.of("type", "integer", "description", "scroll滚动量：1至10向下、-1至-10向上"));
        params.put("targetWindowId", Map.of("type", "string", "description", "仅focus使用，必须来自最近观察windows列表"));
        registry.registerStructured("desktop_act", "基于最近截图执行一个已批准的桌面动作，并返回新截图和新observationId。"
                + "输入成功不代表业务完成，必须检查操作后界面。未知结果禁止重试，先重新观察。", params, args -> {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                String action = node.path("action").asText();
                Integer x = optionalInt(node, "x"), y = optionalInt(node, "y");
                if (java.util.Set.of("click", "double_click", "right_click").contains(action) && (x == null || y == null))
                    throw new IllegalArgumentException("点击必须提供x和y");
                if ("scroll".equals(action) && ((x == null) != (y == null)))
                    throw new IllegalArgumentException("滚动位置必须同时提供x和y");
                return desktop.act(node.path("observationId").asText(), new DesktopBackend.Action(action, x, y,
                        node.path("text").asText(null), node.path("key").asText(null), optionalInt(node, "amount"),
                        node.path("targetWindowId").asText(null)));
            } catch (Exception e) { return com.miniagent.agent.tool.ToolResult.failure(
                    com.miniagent.agent.tool.ToolErrorCode.INVALID_ARGUMENT, "无效桌面参数", false); }
        });
        registry.registerStructured("desktop_release", "释放当前任务的桌面输入租约，使先前observationId失效；不会操作其他任务的按键或窗口。",
                Map.of(), ignored -> desktop.release());
    }
    private static Integer optionalInt(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("无效整数参数: " + field);
        return value.intValue();
    }
}
