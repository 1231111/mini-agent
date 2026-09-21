package com.miniagent.agent.task;

import com.miniagent.common.MessageConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信号提取只回答「文本里出现了什么」。这些用例守住几个曾经踩过的坑：
 * 写 md 不等于图片入文档、生成 Excel 不等于发布、问号不等于内存问答。
 */
class TaskSignalMatcherTest {

    private TaskSignalMatcher signals;
    private TaskSignalProperties props;

    @BeforeEach
    void setUp() {
        props = new TaskSignalProperties();
        TaskSignalProperties.Rules r = props.getRules();
        r.setImageIntoDocSignals(List.of(
                "(?i)(替换|写入|插入).{0,40}(md|markdown|文档|\\.md)"));
        r.setFileSignals(List.of("(?i)\\.md\\b|write_file|xlsx|docx"));
        r.setWebSignals(List.of("(?i)https?://", "(?i)搜索|检索|调研|搜一下|查一下"));
        r.setQuestionSignals(List.of(
                "(?i)^\\s*(你好|hello)\\s*[?？]*\\s*$"));
        r.setTaskActionSignals(List.of("(?i)生成|写|创建|部署|搜索"));
        r.setQuestionMaxLen(80);
        signals = new TaskSignalMatcher(props);
    }

    @Test
    void writeMdIsNotImageIntoDoc() {
        assertFalse(signals.imageIntoDoc("写入工作区文件 qa_c01_ai_news.md"));
        assertFalse(signals.imageIntoDoc("先把 mermaid 写入 architecture.mmd"));
    }

    @Test
    void pictureIntoMdIsImageIntoDoc() {
        assertTrue(signals.imageIntoDoc("把图片写入 notes.md"));
    }

    @Test
    void generateExcelIsNotPublish() {
        assertFalse(signals.looksLikePublish(
                "请用 write_xlsx 生成 Excel 文件 qa_m02_scores.xlsx"));
        assertTrue(signals.looksLikePublish("把服务发布到生产环境"));
    }

    @Test
    void arithmeticIsFactualQuestion() {
        assertTrue(signals.factualQuestion("9乘以7等于多少？只回答数字，不要解释。"));
        assertFalse(signals.factualQuestion("写一个 qa_s03_hello.txt"));
    }

    @Test
    void followUpQuestionMarkIsNotFactualQuestion() {
        assertFalse(signals.factualQuestion("Suno Pro是付费的吗？"));
        assertFalse(signals.inMemoryTask("Suno Pro是付费的吗？"));
        assertFalse(signals.factualQuestion("你能帮我注册Pro吗？"));
        assertFalse(signals.factualQuestion("suno 的官网地址是什么？我去注册"));
    }

    @Test
    void sortIsInMemoryTask() {
        assertTrue(signals.inMemoryTask(
                "把「apple、banana、cherry」按字母顺序排列，只输出排序后的英文单词，用逗号分隔，不要其它文字。"));
    }

    @Test
    void singleTxtWriteIsSimpleFileDelivery() {
        assertTrue(signals.simpleFileDelivery(
                "请在工作区写入文件 qa_s03_hello.txt，内容恰好一行 MiniAgentQA-S03。写完后只回复路径。"));
        assertFalse(signals.simpleFileDelivery(
                "请在工作区写两个文件：qa_c02_app.py 和 qa_c02_requirements.txt"));
    }

    @Test
    void singleXlsxWriteIsSimpleFileDelivery() {
        assertTrue(signals.simpleFileDelivery(
                "请用 write_xlsx 生成 qa_m02_scores.xlsx，只含一行表头。"));
    }

    @Test
    void searchTodayNewsIsWebNotSimpleFile() {
        String q = "帮我搜索今日 AI 技术最新动态";
        assertTrue(signals.needsWeb(q));
        assertFalse(signals.simpleFileDelivery(q));
    }

    @Test
    void lightTurnOnlyWhenQuestionAndNoAction() {
        assertTrue(signals.of("你好").lightTurn());
        assertFalse(signals.of("你好，帮我搜索今天的新闻").lightTurn());
        assertFalse(signals.of("把架构图画成 architecture.mmd").lightTurn());
    }

    @Test
    void signalsAreIndependentFacts() {
        TaskSignals news = signals.of("帮我搜索今日 AI 技术最新动态");
        assertTrue(news.needsWeb());
        assertFalse(news.needsFiles());
        assertFalse(news.simpleFile());

        TaskSignals file = signals.of("写入工作区文件 qa_c01_ai_news.md");
        assertTrue(file.needsFiles());
        assertFalse(file.needsWeb());

        assertTrue(signals.of("").hits().isEmpty());
    }

    /**
     * 只提文件名不提动作，也算「这轮跟文件有关」。
     *
     * <p>旧词表是围绕写盘动作配的，「读取 test.pdf」命中不了任何一条，
     * 整个文件层判断会落空。</p>
     */
    @Test
    void fileNameAloneCountsAsFileSignal() {
        assertTrue(signals.needsFiles("读取 test.pdf 里有多少页"));
        assertTrue(signals.needsFiles("帮我看看 data.csv 的列名"));
        assertFalse(signals.needsFiles("帮我搜索今日 AI 技术最新动态"));
    }

    /**
     * 读/写两类动词决定这轮是读文件还是写文件。
     *
     * <p>两类同时出现时以写为准：交付物才是这轮的目的，</p>
     */
    @Test
    void readVerbDistinguishesReadFromWrite() {
        assertTrue(signals.readsFile("分析 test.xlsx 各月数据"));
        assertTrue(signals.readsFile("读取 test.pdf"));
        assertFalse(signals.readsFile("生成 sales.xlsx"));
        assertFalse(signals.readsFile("写一个 hello.txt"));
        assertFalse(signals.readsFile("分析 test.xlsx 并生成 report.md"));
        assertFalse(signals.readsFile("搜索今日新闻"));
    }

    /**
     * 读类动词不参与「要不要动手」的判定。
     *
     * <p>读的对象可能是消息里自带的一段话，不必然要动工具；
     * 把它算进动手信号会让带「分析」二字的寒暄被挤出轻问答。</p>
     */
    @Test
    void readVerbAloneIsNotActionBearing() {
        TaskSignals s = signals.of("帮我分析下这段话");
        assertTrue(s.readsFile());
        assertFalse(s.actionBearing());
    }

    /**
     * taskAction 必须是可观察的事实，不能只当内部阀门。
     *
     * <p>线上「帮我写一首歌曲，并实现真人唱歌」这一轮命中了词表里的「写」与「实现」，
     * 但因为它不是 TaskSignals 的一位，{@code describe()} 打出 {@code none} ——
     * 日志上看着什么都没命中；而这段信号文本会被拼进系统提示词，
     * 等于告诉模型「这轮没观察到任何动手需求」，模型于是只写文字、不调工具。</p>
     */
    @Test
    void taskActionIsAnObservableSignal() {
        TaskSignals s = signals.of("帮我写一首歌曲，并实现真人唱歌");
        assertTrue(s.taskAction());
        assertTrue(s.actionBearing());
        assertTrue(s.hits().contains("action"));
        assertNotEquals("none", s.describe());
        assertFalse(s.lightTurn());

        assertFalse(signals.of("你好").taskAction());
    }

    /**
     * 零个文件名不是「单文件短指令」。
     *
     * <p>file-signals 词表里有 {@code write_file} 这一条，提到工具名就能让
     * {@code needsFiles} 为真，而一个真实文件名都没有。旧实现的
     * {@code extHits <= 1} 在 0 上放行，于是前端拼出的确认语被判成单文件写盘。</p>
     */
    @Test
    void zeroFileNameIsNotSimpleFileDelivery() {
        assertTrue(signals.needsFiles("已批准「write_file」，请继续执行。"));
        assertFalse(signals.simpleFileDelivery("已批准「write_file」，请继续执行。"));
        // 恰好一个文件名才算
        assertTrue(signals.simpleFileDelivery("请用 write_xlsx 生成 qa_m02_scores.xlsx，只含一行表头。"));
    }

    /**
     * 系统控制语不产生任何任务信号，也不允许它拉起任务图。
     *
     * <p>确认语由前端/后端拼出来、当用户消息送进主循环，它不是用户打的字。
     * 不排除的话它会命中 {@code write_file} 而 {@code needsFiles=true}；
     * 更要命的是旧实现恰好靠 {@code simpleFile} 挡住了
     * {@code needsFiles && !simpleFile} —— 一旦把 extHits 收紧，
     * 一句批准语就会拉起一整张 Planner 任务图。</p>
     */
    @Test
    void systemControlMessageCarriesNoSignalsAndNoPlan() {
        String approval = MessageConstants.SYSTEM_MESSAGE_PREFIX + "已批准「write_file」，请继续执行。";
        assertTrue(MessageConstants.isSystemControlMessage(approval));

        TaskPlan plan = new TaskPlanFactory(signals, props).build(approval);
        assertEquals(TaskSignals.NONE, plan.signals());
        assertFalse(plan.requiresStructuredPlan());

        // 用户原话照常提取信号
        String plain = "写入工作区文件 qa_c01_ai_news.md";
        assertFalse(MessageConstants.isSystemControlMessage(plain));
        assertTrue(new TaskPlanFactory(signals, props).build(plain).signals().needsFiles());
    }
}
