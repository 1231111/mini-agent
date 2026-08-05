package com.miniagent.agent.intent;

import java.util.regex.Pattern;

/**
 * 复杂任务启发式检测：用于强制结构化 todo 计划，避免模型直接开跑导致跑偏。
 * 不做额外 LLM 调用。
 */
public final class ComplexTaskDetector {

    private ComplexTaskDetector() {}

    /** 明确的多步 / 批量 / 工程类信号 */
    private static final Pattern COMPLEX_SIGNAL = Pattern.compile(
            "(?i)("
                    + "一整套|完整(的)?(项目|系统|方案|架构|平台)|多模块|多个文件|分别(实现|生成|写)|并且.*并且"
                    + "|拆成|分步|步骤|阶段|里程碑|脚手架|骨架|monorepo|微服务"
                    + "|架构设计|技术方案|系统设计|从零(开始|搭建)|端到端"
                    + "|至少\\s*\\d+|不少于\\s*\\d+|\\d+\\s*个(文件|模块|接口|页面|服务)"
                    + "|generate\\s+(a\\s+)?(full|complete|entire)|scaffold|multi[- ]?step|end[- ]to[- ]end"
                    + "|然后.*(再|接着|最后)|先.*再.*最后"
                    + ")"
    );

    /** 批量产出信号（应强制 delegate_task） */
    private static final Pattern BATCH_SIGNAL = Pattern.compile(
            "(?i)("
                    + "批量|多个模块|各个模块|每个模块|一套代码|全套|所有文件"
                    + "|tenant|计费|权限|认证|多个\\s*\\.(java|sql|md|ts|py)"
                    + "|batch|multiple\\s+files|all\\s+modules"
                    + ")"
    );

    public static boolean isComplex(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String text = userMessage.trim();
        if (COMPLEX_SIGNAL.matcher(text).find()) return true;
        // 长需求且含多个动作动词
        if (text.length() >= 100) {
            int verbs = countMatches(text, "(生成|实现|编写|设计|搭建|开发|创建|改造|重构|调研|验证)");
            if (verbs >= 3) return true;
        }
        // 同时点名多种交付物
        int artifacts = 0;
        if (text.contains(".md") || text.contains("文档") || text.contains("方案")) artifacts++;
        if (text.contains(".java") || text.contains("代码") || text.contains("接口")) artifacts++;
        if (text.contains(".sql") || text.contains("数据库") || text.contains("表结构")) artifacts++;
        if (text.contains("测试") || text.contains("用例")) artifacts++;
        return artifacts >= 2;
    }

    public static boolean looksLikeBatch(String userMessage) {
        if (userMessage == null) return false;
        return BATCH_SIGNAL.matcher(userMessage).find() || isComplex(userMessage);
    }

    private static int countMatches(String text, String regex) {
        var m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
