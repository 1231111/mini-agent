package com.miniagent.agent.intent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic task-complexity analysis layered on top of configured intent signals.
 * Capability inference is planning metadata only; it never grants tool permission.
 */
@Component
public class ComplexTaskDetector {

    private static final List<Pattern> COORDINATORS = List.of(
            Pattern.compile("并且|同时|以及|还有|另外|此外|然后|接着|最后|其次|首先|第一步|第二步|第三步|第四步|再"),
            Pattern.compile("先.{0,30}(再|然后|接着|最后)"),
            Pattern.compile("既.{0,30}又"),
            Pattern.compile("\\b(and then|then|finally|next|also)\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern ACTION_VERB = Pattern.compile(
            "生成|创建|修改|编辑|替换|删除|执行|运行|部署|实现|开发|搭建|设计|构建|重构|"
                    + "写入|保存|导出|下载|上传|发布|交付|编写|读取|查询|检索|搜索|爬取|抓取|"
                    + "分析|整理|汇总|统计|校验|验证|测试|排查|定位|修复|优化|"
                    + "\\b(create|build|develop|implement|edit|write|read|search|crawl|analy[sz]e|"
                    + "test|verify|deploy|run|execute|fix|refactor|generate)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_ENTITY = Pattern.compile(
            "(?:[\\w.-]+\\.)(?:md|markdown|txt|java|py|js|ts|tsx|jsx|go|rs|cpp|c|sql|json|"
                    + "yaml|yml|xml|html|css|sh|bat|ps1|docx?|xlsx?|pptx?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_ENTITY = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    private static final Pattern WEB = Pattern.compile(
            "搜索|检索|网页|网站|互联网|爬取|抓取|浏览|\\b(web|search|crawl|scrape|http)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile(
            "代码|开发|实现|接口|模块|架构|重构|编程|Java|Python|JavaScript|TypeScript|CRUD|"
                    + "\\b(code|api|class|function|compile|test)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_WRITE = Pattern.compile(
            "写文档|生成.{0,12}(文档|报告|文件|Markdown)|保存|导出|写入|编辑.{0,8}文件|"
                    + "\\b(write|save|export).{0,16}(file|document|report|markdown)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL = Pattern.compile(
            "shell|命令行|终端|脚本|执行命令|运行命令|PowerShell|Bash|cmd\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int STRUCTURAL_MIN_SIGNALS = 2;
    private static final int LENGTH_COMPLEX = 2000;
    private static final int SENTENCE_COMPLEX = 3;

    private IntentSignalMatcher signals;

    public ComplexTaskDetector() {}

    @Autowired
    public ComplexTaskDetector(IntentSignalMatcher signals) {
        this.signals = signals;
    }

    public record ComplexityResult(boolean isComplex,
                                   int structuralSignalCount,
                                   List<String> suggestedSteps,
                                   Set<String> requiredCapabilities) {
        public ComplexityResult {
            suggestedSteps = List.copyOf(suggestedSteps);
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }
    }

    public ComplexityResult analyze(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new ComplexityResult(false, 0, List.of(), Set.of());
        }
        String text = userMessage.trim();
        int structural = structuralSignals(text);
        Set<String> capabilities = inferCapabilities(text);
        boolean configuredSignal = signals != null && signals.complex(text);
        boolean explicitCompoundAction = actionCount(text) >= 2;
        boolean complex = configuredSignal || structural >= STRUCTURAL_MIN_SIGNALS
                || explicitCompoundAction || (hasAction(text) && !capabilities.isEmpty());

        List<String> steps = complex ? inferSteps(text, capabilities) : List.of();
        return new ComplexityResult(complex, structural, steps, capabilities);
    }

    public boolean isComplex(String userMessage) {
        return analyze(userMessage).isComplex();
    }

    /** Number of independent deterministic structure features, currently 0..5. */
    public int structuralSignals(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return 0;
        }
        String text = userMessage.trim();
        int hits = 0;
        if (text.length() >= LENGTH_COMPLEX) hits++;
        if (sentenceCount(text) >= SENTENCE_COMPLEX) hits++;
        if (hasCoordinator(text)) hits++;
        if (actionCount(text) >= 2) hits++;
        if (entityCount(text) >= 2) hits++;
        return hits;
    }

    private static Set<String> inferCapabilities(String text) {
        Set<String> capabilities = new LinkedHashSet<>();
        if (WEB.matcher(text).find()) capabilities.add("web");
        if (CODE.matcher(text).find()) capabilities.add("code");
        if (FILE_WRITE.matcher(text).find()) capabilities.add("file_write");
        if (SHELL.matcher(text).find()) capabilities.add("shell");
        return capabilities;
    }

    private static List<String> inferSteps(String text, Set<String> capabilities) {
        List<String> steps = new ArrayList<>();
        if (capabilities.contains("web")) steps.add("搜索并收集相关信息");
        if (capabilities.contains("code")) steps.add("设计架构并实现代码");
        if (capabilities.contains("shell")) steps.add("在授权后执行并验证命令或脚本");
        if (capabilities.contains("file_write")) steps.add("生成并校验交付文件");
        if (steps.isEmpty()) {
            steps.add("拆分任务并确认各步骤的完成条件");
            steps.add("按依赖顺序执行并验证结果");
        } else if (steps.size() == 1 && (actionCount(text) >= 2 || hasCoordinator(text))) {
            steps.add("汇总并验证最终结果");
        }
        return List.copyOf(steps);
    }

    private static int sentenceCount(String text) {
        int count = 0;
        for (String part : text.split("[。！？!?；;\\n]+")) {
            if (!part.isBlank()) count++;
        }
        return count;
    }

    private static boolean hasCoordinator(String text) {
        return COORDINATORS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    private static boolean hasAction(String text) {
        return ACTION_VERB.matcher(text).find();
    }

    private static int actionCount(String text) {
        int count = 0;
        var matcher = ACTION_VERB.matcher(text);
        while (matcher.find()) count++;
        return count;
    }

    private static int entityCount(String text) {
        int count = 0;
        var files = FILE_ENTITY.matcher(text.toLowerCase(Locale.ROOT));
        while (files.find()) count++;
        var urls = URL_ENTITY.matcher(text);
        while (urls.find()) count++;
        return count;
    }
}
