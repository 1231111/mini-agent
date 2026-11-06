package com.miniagent.agent.coding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code agent.coding.diagnostics.*} —— 代码诊断反馈的开关与限额。
 *
 * <h2>为什么默认开启</h2>
 *
 * <p>没有这个回路时，模型改完代码只能靠自己想起来跑构建来验证。实测里它经常想不起来，
 * 表现就是「改完了」但根本编译不过。默认开，是为了让「改完必有反馈」成为默认行为，
 * 而不是一个需要用户配置才有的能力。</p>
 *
 * <h2>为什么预算只有 2000 字符</h2>
 *
 * <p>诊断是每轮都可能注入的常驻内容，不是一次性结果。给到几千字符以上，
 * 一旦某个文件批量报错，它会挤掉正常对话的量。2000 字符大约够放 15~20 条整行报错，
 * 足够让模型知道「哪里错了」，不够的部分让它自己去跑构建拿完整输出。</p>
 */
@ConfigurationProperties(prefix = "agent.coding.diagnostics")
public class CodingDiagnosticsProperties {

    /** 总开关。关掉后完全不做检查，也不注入任何内容。 */
    private boolean enabled = true;

    /** 单次注入的诊断文本上限（字符）。超了按文件截断并注明还有多少未展示。 */
    private int maxBudgetChars = 2000;

    /** 单个文件的检查时限（秒）。到点强杀子进程并返回空，不阻塞本轮 LLM 请求。 */
    private int perFileTimeoutSeconds = 8;

    /** 启用的检查器 id；未装对应工具链的实现会自动跳过。 */
    private List<String> providers = List.of("java", "typescript", "python");

    /** 单轮最多检查多少个文件，防止一次批量改写触发几十次编译。 */
    private int maxFilesPerTurn = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBudgetChars() {
        return maxBudgetChars;
    }

    public void setMaxBudgetChars(int maxBudgetChars) {
        this.maxBudgetChars = maxBudgetChars;
    }

    public int getPerFileTimeoutSeconds() {
        return perFileTimeoutSeconds;
    }

    public void setPerFileTimeoutSeconds(int perFileTimeoutSeconds) {
        this.perFileTimeoutSeconds = perFileTimeoutSeconds;
    }

    public List<String> getProviders() {
        return providers;
    }

    public void setProviders(List<String> providers) {
        this.providers = providers;
    }

    public int getMaxFilesPerTurn() {
        return maxFilesPerTurn;
    }

    public void setMaxFilesPerTurn(int maxFilesPerTurn) {
        this.maxFilesPerTurn = maxFilesPerTurn;
    }
}
