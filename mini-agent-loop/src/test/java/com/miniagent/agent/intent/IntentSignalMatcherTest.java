package com.miniagent.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntentSignalMatcherTest {

    private IntentSignalMatcher signals;

    @BeforeEach
    void setUp() {
        IntentProperties props = new IntentProperties();
        IntentProperties.Rules r = props.getRules();
        r.setImageIntoDocSignals(List.of(
                "(?i)(替换|写入|插入).{0,40}(md|markdown|文档|\\.md)"));
        r.setFileSignals(List.of("(?i)\\\\.md\\\\b|write_file|xlsx|docx"));
        r.setWebSignals(List.of("(?i)https?://"));
        r.setQuestionSignals(List.of(
                "(?i)^\\\\s*(你好|hello)\\\\s*[?？]*\\\\s*$"));
        r.setTaskActionSignals(List.of("(?i)生成|写|创建|部署|搜索"));
        r.setQuestionMaxLen(80);
        signals = new IntentSignalMatcher(props);
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
}
