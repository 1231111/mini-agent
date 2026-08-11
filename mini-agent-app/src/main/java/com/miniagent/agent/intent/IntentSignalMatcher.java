package com.miniagent.agent.intent;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把配置里的正则编译成可匹配器。场景词只来自 {@link IntentProperties}。
 */
@Component
public class IntentSignalMatcher {

    private final IntentProperties props;
    private List<Pattern> web = List.of();
    private List<Pattern> file = List.of();
    private List<Pattern> imageIntoDoc = List.of();
    private List<Pattern> pureImage = List.of();
    private List<Pattern> question = List.of();
    private List<Pattern> taskAction = List.of();
    private List<Pattern> continueSig = List.of();
    private List<Pattern> complex = List.of();
    private List<Pattern> imageAndDoc = List.of();

    public IntentSignalMatcher(IntentProperties props) {
        this.props = props;
        compile();
    }

    @PostConstruct
    void compile() {
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

    public boolean needsWeb(String text) { return any(web, text); }
    public boolean needsFiles(String text) { return any(file, text); }
    public boolean imageIntoDoc(String text) { return any(imageIntoDoc, text); }
    public boolean pureImage(String text) { return any(pureImage, text); }
    public boolean taskAction(String text) { return any(taskAction, text); }
    public boolean continueTask(String text) { return any(continueSig, text); }
    public boolean complex(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        if (any(imageAndDoc, t) || any(complex, t)) return true;
        if (t.length() >= 3000) return true;
        return false;
    }

    public boolean questionIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        if (t.length() > props.getRules().getQuestionMaxLen()) return false;
        if (taskAction(t) || pureImage(t)) return false;
        return anyFullMatch(question, t);
    }

    private static boolean any(List<Pattern> patterns, String text) {
        if (text == null || text.isBlank() || patterns == null || patterns.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean anyFullMatch(List<Pattern> patterns, String text) {
        if (text == null || text.isBlank() || patterns == null || patterns.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(text).matches()) return true;
        }
        return false;
    }

    private static List<Pattern> compileAll(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Pattern> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(out);
    }
}
