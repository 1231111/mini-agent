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
        if (StringUtils.isBlank(text)) return m;
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
        if (!hits.isEmpty()) m.put(group, hits);
    }

    public boolean needsWeb(String text) { return any(web, text); }
    public boolean needsFiles(String text) { return any(file, text); }
    public boolean imageIntoDoc(String text) { return any(imageIntoDoc, text); }
    public boolean pureImage(String text) { return any(pureImage, text); }
    public boolean taskAction(String text) { return any(taskAction, text); }
    public boolean continueTask(String text) { return any(continueSig, text); }
    public boolean complex(String text) {
        if (StringUtils.isBlank(text)) return false;
        String t = text.trim();
        if (any(imageAndDoc, t) || any(complex, t)) return true;
        if (t.length() >= 3000) return true;
        return false;
    }

    public boolean questionIntent(String text) {
        if (StringUtils.isBlank(text)) return false;
        String t = text.trim();
        if (t.length() > props.getRules().getQuestionMaxLen()) return false;
        if (taskAction(t) || pureImage(t)) return false;
        return anyFullMatch(question, t);
    }

    private static boolean any(List<Pattern> patterns, String text) {
        if (StringUtils.isBlank(text) || Objects.isNull(patterns) || patterns.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean anyFullMatch(List<Pattern> patterns, String text) {
        if (StringUtils.isBlank(text) || Objects.isNull(patterns) || patterns.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(text).matches()) return true;
        }
        return false;
    }

    private static List<Pattern> compileAll(List<String> raw) {
        if (Objects.isNull(raw) || raw.isEmpty()) return List.of();
        List<Pattern> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (StringUtils.isBlank(s)) continue;
            out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(out);
    }
}
