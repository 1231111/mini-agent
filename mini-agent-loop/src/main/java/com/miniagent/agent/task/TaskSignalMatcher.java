package com.miniagent.agent.task;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把配置里的正则编译成匹配器，从用户消息里提取事实信号。
 * 词表只来自 {@link TaskSignalProperties}。
 *
 * <p>这个类只回答「文本里出现了什么」，不回答「用户想要什么」。
 * 少部分判断需要结合上下文（例如「架构图」必须走文件交付），
 * 那些判断写在这里并注明原因，而不是把它归结成一个类别。</p>
 */
@Component
public class TaskSignalMatcher {

    /**
     * 文件名信号：消息里出现带扩展名的文件名，就是「这轮跟文件有关」的事实。
     *
     * <p>不区分读写方向 —— 方向由 {@link #READ_VERB} / {@link #WRITE_VERB} 描述。
     * 也不进可热更词表：旧规则集普遍不含 pdf/csv 这类读取型扩展名，靠配置补不齐。</p>
     */
    private static final Pattern FILE_EXT = Pattern.compile(
            "\\.(txt|md|markdown|java|py|json|xml|yaml|yml|toml|properties|conf|log"
                    + "|html|css|js|tsx?|go|rs|sh|bat|ps1|sql|ipynb|mmd|svg"
                    + "|xlsx|xls|csv|docx|doc|pptx|ppt|pdf|png|jpe?g|zip)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 读取/分析已有内容的动词。 */
    private static final Pattern READ_VERB = Pattern.compile(
            "(?i)(读取|读一下|读读|解析|分析|统计|汇总|提取|抽取|审阅|检查|梳理|总结|看一下|看看"
                    + "|read|parse|analyze|analyse|inspect|review|summarize|extract)");

    /** 生成/写盘的动词。与 {@link #READ_VERB} 同时命中时以写为准：交付物才是这轮的目的。 */
    private static final Pattern WRITE_VERB = Pattern.compile(
            "(?i)(生成|创建|新建|写入|写出|写到|导出|保存|产出|整理成|输出|做一份|写一份|做一个|写一个"
                    + "|write|create|generate|export|save|produce)");

    /** 多文件/多产物表述：出现这些词就不是「单个文件短指令」。 */
    private static final Pattern MULTI_ARTIFACT = Pattern.compile(
            "(?s).*(两个|2\\s*个|两份|分别|各自|multiple|flask|requirements).*");

    @Autowired
    private TaskSignalProperties props;

    private List<Pattern> web = List.of();
    private List<Pattern> file = List.of();
    private List<Pattern> imageIntoDoc = List.of();
    private List<Pattern> pureImage = List.of();
    private List<Pattern> question = List.of();
    private List<Pattern> taskAction = List.of();
    private List<Pattern> continueSig = List.of();
    private List<Pattern> complex = List.of();
    private List<Pattern> imageAndDoc = List.of();

    public TaskSignalMatcher() {
    }

    /** 同包测试：绕过 Spring 注入 */
    TaskSignalMatcher(TaskSignalProperties props) {
        this.props = props;
        reload();
    }

    @PostConstruct
    void compile() {
        reload();
    }

    /** 词表重新编译。 */
    public synchronized void reload() {
        TaskSignalProperties.Rules r = props.getRules();
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

    /**
     * 一次性算出本轮全部事实。
     *
     * <p>带图与否不参与判断：曾经有一条「带图且短消息走截图点评」的快路径，
     * 已随三层分类一起删除。图片在本轮只作为多模态输入，不改变任务判据。</p>
     *
     * @param text 用户消息原文
     */
    public TaskSignals of(String text) {
        String t = text == null ? "" : text.trim();
        return new TaskSignals(
                needsWeb(t),
                needsFiles(t),
                readsFile(t),
                deliverableDiagram(t),
                pureImage(t),
                imageIntoDoc(t),
                simpleFileDelivery(t),
                questionIntent(t) || inMemoryTask(t),
                complex(t),
                taskAction(t),
                continueTask(t),
                looksLikePublish(t));
    }

    public boolean needsWeb(String text) {
        return any(web, text);
    }

    /**
     * 这轮跟文件有关。
     *
     * <p>两条证据任一成立即为真：命中配置词表，或消息里出现带扩展名的文件名。
     * 后者是必需的 —— 词表是围绕「写盘动作」配的（写到桌面/保存到/文档），
     * 「读取 test.pdf」这类只提文件名不提动作的说法命中不了任何一条，
     * 会让整个文件层判断落空。</p>
     */
    public boolean needsFiles(String text) {
        return any(file, text) || mentionsFile(text);
    }

    /** 消息里出现了带扩展名的文件名。 */
    public boolean mentionsFile(String text) {
        return text != null && FILE_EXT.matcher(text).find();
    }

    /**
     * 要读取/分析已有内容，而不是生成新内容。
     *
     * <p>必须同时有读类动词、且没有写类动词。两类动词同时出现时以写为准：
     * 「分析 test.xlsx 并生成 report.md」的交付物是 report.md，
     * 按读取去拆会把交付物丢掉。</p>
     */
    public boolean readsFile(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return READ_VERB.matcher(text).find() && !WRITE_VERB.matcher(text).find();
    }

    /**
     * 「图写入文档」必须真有配图词。旧词表里
     * {@code 写入.{0,40}.md} 会把「写入 news.md」打成 image-into-doc。
     */
    public boolean imageIntoDoc(String text) {
        return any(imageIntoDoc, text) && hasPictureToken(text);
    }

    public boolean pureImage(String text) {
        return any(pureImage, text);
    }

    public boolean taskAction(String text) {
        return any(taskAction, text);
    }

    public boolean continueTask(String text) {
        return any(continueSig, text);
    }

    public boolean complex(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (any(imageAndDoc, t) || any(complex, t)) {
            return true;
        }
        return t.length() >= 3000;
    }

    /**
     * 架构图/mermaid 必须走文件交付，不能被文生图短路。
     * 不进可热更词表：旧规则集往往不含这些词。
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
     * 单文件、短指令写盘。有文件名时规划器仍会编 1 节点图，不再空转主循环。
     */
    public boolean simpleFileDelivery(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > 280 || complex(t) || needsWeb(t) || deliverableDiagram(t)) {
            return false;
        }
        Matcher m = FILE_EXT.matcher(t);
        int extHits = 0;
        while (m.find()) {
            extHits++;
        }
        // 必须真有文件信号。「搜索/运行」等动作词不是写盘。
        if (!needsFiles(t)) {
            return false;
        }
        if (MULTI_ARTIFACT.matcher(t).matches()) {
            return false;
        }
        // 恰好一个文件名。不能放宽到「0 个也放行」——needsFiles 为真不等于点了名：
        // file-signals 词表里有 write_file 这一条，提到工具名就能让 needsFiles 为真，
        // 而一个真实文件名都没有。这个信号叫 simpleFileDelivery（单文件短指令），
        // 在零个文件上放行是自相矛盾的：它本来的用途就是「有文件名时不拉任务图」。
        return extHits == 1;
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
        // 问号不是内存问答：跨轮追问（「Suno Pro是付费的吗？」）留给模型自己判。
        return t.contains("等于") || t.contains("多少")
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
        if (taskAction(t) || pureImage(t) || needsWeb(t) || needsFiles(t)
                || deliverableDiagram(t)) {
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
