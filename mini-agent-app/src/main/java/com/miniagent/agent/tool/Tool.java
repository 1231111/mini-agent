package com.miniagent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具抽象：一个可被 Agent 调用的外部能力。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Tool {

    private String name;
    private String description;
    /** JSON Schema 格式的参数描述 */
    @Builder.Default
    private Map<String, Object> parameters = Collections.emptyMap();
    /** 执行函数：接收参数 JSON 字符串，返回结果字符串 */
    private Function<String, String> handler;

    /**
     * 执行工具调用
     * @param argumentsJson LLM 返回的参数 JSON
     * @return 执行结果
     */
    public String execute(String argumentsJson) {
        if (handler == null) {
            return "错误：工具 " + name + " 未配置执行器";
        }
        try {
            return handler.apply(argumentsJson);
        } catch (Exception e) {
            return "工具执行错误 [" + name + "]: " + e.getMessage();
        }
    }
}
