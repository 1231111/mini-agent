package com.miniagent.agent.intent;

import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 把配置里的正则编译成可匹配器。场景词只来自 {@link IntentProperties}。
 */
@Component
public class IntentSignalMatcher {

    @Autowired

    private IntentProperties props;
    private List<Pattern> web = List.of();
    private List<Pattern> file = List.of();
    private List<Pattern> imageIntoDoc = List.of();
    private List<Pattern> pureImage = List.of();
    private List<Pattern> question = List.of();
    private List<Pattern> taskAction = List.of();
    private List<Pattern> continueSig = List.of();
    private List<Pattern> complex = List.of();
    private List<Pattern> imageAndDoc = List.of();

    /** 同包测试：绕过 Spring 注入 */
    IntentSignalMatcher(IntentProperties props) {
        this.props = props;
        compile();
    }

    public IntentSignalMatcher() {}

    @PostConstruct
    void compile() {
        reload();
    }

    /** MySQL / YAML 规则变更后热编译 */
    public synchronized void reload() {
        IntentProperties.Rules r = props.getRules();
        web = compileAll(r.getWebSignals());
        file = compileAll(r.getFileSignals());
        imageIntoDoc = compileAll(r.getImageIntoDocSignals());
        pureImage = compileAll(r.getPureImageSignals());
        question = compileAll(r.getQuestionSignals());
        taskAction = compileAll(r.getTaskActionSignals());
        continueSig = compileAll(r.getContinueSignals());
        complex = compileAll(r.getComplexSignals());
        imageAndDoc = compileAll(r.getImageAndDocSignals());
    }

    /** 可观测：当前文本命中了哪些信号组 */
    public Map<String, Object> describeMatches(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (StringUtils.isBlank(text)) {
            return m;
        }
        String t = text.trim();
        putHit(m, IntentRuleRuntime.GROUP_WEB, web, t, false);
        putHit(m, IntentRuleRuntime.GROUP_FILE, file, t, false);
        putHit(m, IntentRuleRuntime.GROUP_IMAGE_INTO_DOC, imageIntoDoc, t, false);
        putHit(m, IntentRuleRuntime.GROUP_PURE_IMAGE, pureImage, t, false);
        putHit(m, IntentRuleRuntime.GROUP_QUESTION, question, t, true);
        putHit(m, IntentRuleRuntime.GROUP_TASK_ACTION, taskAction, t, false);
        putHit(m, IntentRuleRuntime.GROUP_CONTINUE, continueSig, t, false);
        putHit(m, IntentRuleRuntime.GROUP_COMPLEX, complex, t, false);
        putHit(m, IntentRuleRuntime.GROUP_IMAGE_AND_DOC, imageAndDoc, t, false);
        return m;
    }

    private static void putHit(Map<String, Object> m, String group, List<Pattern> patterns,
                               String text, boolean fullMatch) {
        List<String> hits = new ArrayList<>();
        for (Pattern p : patterns) {
            var matcher = p.matcher(text);
            if (fullMatch ? matcher.matches() : matcher.find()) {
                hits.add(p.pattern());
            }
        }
        if (!hits.isEmpty()) {
            m.put(group, hits);
        }
    }

    public boolean needsWeb(String text) { return any(web, text); }
    public boolean needsFiles(String text) { return any(file, text); }

    /**
     * 「图写入文档」必须真有配图词。旧 MySQL rule_set 里
     * {@code 写入.{0,40}.md} 会把「写入 news.md」打成 image-into-doc。
     */
    public boolean imageIntoDoc(String text) {
        return any(imageIntoDoc, text) && hasPictureToken(text);
    }

    public boolean pureImage(String text) { return any(pureImage, text); }
    public boolean taskAction(String text) { return any(taskAction, text); }
    public boolean continueTask(String text) { return any(continueSig, text); }
    public boolean complex(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (any(imageAndDoc, t) || any(complex, t)) {
            return true;
        }
        if (t.length() >= 3000) {
            return true;
        }
        return false;
    }

    /**
     * 架构图/mermaid 必须走文件交付，不能被文生图短路。
     * 不进可热更规则：旧 rule_set 往往不含这些词。
     */
    public boolean deliverableDiagram(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("架构图") || t.contains("流程图") || t.contains("时序图")
                || t.contains("结构图") || t.contains("mermaid") || t.contains(".mmd")
                || t.contains("render_diagram") || t.contains("出设计图")
                || t.contains("architecture.png") || t.contains("architecture.mmd");
    }

    /** 真发布/上线；「生成并发布 Excel」不算。 */
    public boolean looksLikePublish(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.toLowerCase();
        if (t.contains("上线") || t.contains("部署到") || t.contains("发布到")
                || t.contains("发布上线") || t.contains("生产环境")
                || t.contains("push to prod") || t.contains("drop table")) {
            return true;
        }
        if (!t.contains("发布")) {
            return false;
        }
        return !t.contains("xlsx") && !t.contains("docx") && !t.contains("pptx")
                && !t.contains("write_xlsx") && !t.contains("write_docx")
                && !t.contains("excel") && !t.contains("word")
                && !t.contains("成绩表") && !t.contains("生成");
    }

    /**
     * 单文件、短指令写盘（一行文本 / 单个源码），不走规划器与 todo 闸门。
     */
    public boolean simpleFileDelivery(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > 280 || complex(t) || needsWeb(t) || deliverableDiagram(t)) {
            return false;
        }
        if (!needsFiles(t) && !taskAction(t)) {
            return false;
        }
        if (t.matches("(?s).*(两个|2\\s*个|两份|分别|各自|multiple|flask|requirements).*")) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\.(txt|md|java|py|json|xml|yaml|yml|html|css|js|tsx?|go|rs|properties|xlsx|docx|pptx)\\b",
                Pattern.CASE_INSENSITIVE).matcher(t);
        int extHits = 0;
        while (m.find()) {
            extHits++;
        }
        return extHits <= 1;
    }

    /**
     * 纯内存即可完成的短任务：算术、排序、格式化输出，不写文件不联网。
     */
    public boolean inMemoryTask(String text) {
        if (factualQuestion(text)) {
            return true;
        }
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > props.getRules().getQuestionMaxLen()) {
            return false;
        }
        if (needsFiles(t) || needsWeb(t) || deliverableDiagram(t) || taskAction(t)) {
            return false;
        }
        return t.contains("排列") || t.contains("排序") || t.contains("按字母");
    }

    /**
     * 短问答/算术，不写文件不联网。问候走 {@link #questionIntent}。
     */
    public boolean factualQuestion(String text) {
        if (questionIntent(text)) {
            return true;
        }
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > props.getRules().getQuestionMaxLen()) {
            return false;
        }
        if (taskAction(t) || needsFiles(t) || needsWeb(t) || pureImage(t)
                || deliverableDiagram(t)) {
            return false;
        }
        return t.contains("？") || t.contains("?")
                || t.contains("等于") || t.contains("多少")
                || t.contains("什么是") || t.contains("为什么");
    }

    public boolean questionIntent(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > props.getRules().getQuestionMaxLen()) {
            return false;
        }
        if (taskAction(t) || pureImage(t)) {
            return false;
        }
        return anyFullMatch(question, t);
    }

    private static boolean any(List<Pattern> patterns, String text) {
        if (StringUtils.isBlank(text) || Objects.isNull(patterns) || patterns.isEmpty()) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyFullMatch(List<Pattern> patterns, String text) {
        if (StringUtils.isBlank(text) || Objects.isNull(patterns) || patterns.isEmpty()) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasPictureToken(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("图片") || t.contains("海报") || t.contains("插画")
                || t.contains("壁纸") || t.contains("封面") || t.contains("配图")
                || t.contains("文生图") || t.contains("txt2img") || t.contains("img2img")
                || t.contains("photograph") || t.contains("illustration");
    }

    private static List<Pattern> compileAll(List<String> raw) {
        if (Objects.isNull(raw) || raw.isEmpty()) {
            return List.of();
        }
        List<Pattern> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (StringUtils.isBlank(s)) {
                continue;
            }
            out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(out);
    }
}
