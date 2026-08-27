package com.miniagent.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComplexTaskDetector 单元测试。
 */
class ComplexTaskDetectorTest {

    private ComplexTaskDetector detector;

    @BeforeEach
    void setUp() {
        IntentProperties props = new IntentProperties();
        IntentSignalMatcher signals = new IntentSignalMatcher(props);
        detector = new ComplexTaskDetector(signals);
    }

    // ===== 简单任务 =====

    @Test
    void nullInput_returnsSimple() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(null);
        assertFalse(result.isComplex());
        assertTrue(result.suggestedSteps().isEmpty());
    }

    @Test
    void blankInput_returnsSimple() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze("  ");
        assertFalse(result.isComplex());
    }

    @Test
    void singleQuestion_returnsSimple() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze("什么是RAG？");
        assertFalse(result.isComplex());
    }

    @Test
    void shortGreeting_returnsSimple() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze("你好");
        assertFalse(result.isComplex());
    }

    // ===== 复杂任务 - 多连词 =====

    @Test
    void multipleCoordinators_isComplex() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "帮我搜索LangChain4j文档，并且整理成学习笔记，然后生成测试代码");
        assertTrue(result.isComplex());
        assertFalse(result.suggestedSteps().isEmpty());
    }

    @Test
    void sequentialSteps_isComplex() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "先搜索相关资料，再分析数据，最后生成报告");
        assertTrue(result.isComplex());
    }

    // ===== 复杂任务 - 多动作动词 =====

    @Test
    void multipleActionVerbs_isComplex() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "创建一个Spring Boot项目，实现用户注册接口，编写单元测试，部署到服务器");
        assertTrue(result.isComplex());
    }

    // ===== 复杂任务 - 多实体 =====

    @Test
    void multipleEntities_isComplex() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "读取 config.yaml 和 database.sql 文件，分析它们的结构");
        assertTrue(result.isComplex());
    }

    // ===== 复杂任务 - 长文本 =====

    @Test
    void longText_isComplex() {
        String longText = "我需要一个完整的解决方案。".repeat(200);
        ComplexTaskDetector.ComplexityResult result = detector.analyze(longText);
        assertTrue(result.isComplex());
    }

    // ===== 复杂任务 - 多句子 =====

    @Test
    void multipleSentences_isComplex() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "第一步分析需求。第二步设计架构。第三步实现功能。第四步测试验证。");
        assertTrue(result.isComplex());
    }

    // ===== 结构信号计数 =====

    @Test
    void structuralSignals_emptyText() {
        assertEquals(0, detector.structuralSignals(null));
        assertEquals(0, detector.structuralSignals(""));
        assertEquals(0, detector.structuralSignals("  "));
    }

    @Test
    void structuralSignals_shortText() {
        assertEquals(0, detector.structuralSignals("你好"));
    }

    @Test
    void structuralSignals_longText() {
        String longText = "x".repeat(3000);
        int signals = detector.structuralSignals(longText);
        assertTrue(signals >= 1, "长文本应有至少1个结构信号");
    }

    @Test
    void structuralSignals_multiSentence() {
        int signals = detector.structuralSignals("第一句。第二句。第三句。");
        assertTrue(signals >= 1, "多句子应有至少1个结构信号");
    }

    // ===== 步骤推断 =====

    @Test
    void analyze_researchTask_suggestsSearchSteps() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "搜索并整理Spring Boot最佳实践");
        assertTrue(result.isComplex());
        assertTrue(result.suggestedSteps().stream()
                .anyMatch(s -> s.contains("搜索") || s.contains("信息")),
                "应建议搜索相关步骤");
    }

    @Test
    void analyze_codeTask_suggestsCodeSteps() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "开发一个用户管理模块，实现CRUD接口");
        assertTrue(result.isComplex());
        assertTrue(result.suggestedSteps().stream()
                .anyMatch(s -> s.contains("代码") || s.contains("架构")),
                "应建议代码相关步骤");
    }

    // ===== 能力推断 =====

    @Test
    void analyze_webTask_infersWebCapability() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "搜索网页并爬取数据");
        assertTrue(result.requiredCapabilities().contains("web"));
    }

    @Test
    void analyze_codeTask_infersCodeCapability() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "开发Java代码实现功能");
        assertTrue(result.requiredCapabilities().contains("code"));
    }

    @Test
    void analyze_fileTask_infersFileCapability() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "写文档并生成Markdown报告");
        assertTrue(result.requiredCapabilities().contains("file_write"));
    }

    @Test
    void analyze_shellTask_infersShellCapability() {
        ComplexTaskDetector.ComplexityResult result = detector.analyze(
                "执行shell命令运行脚本");
        assertTrue(result.requiredCapabilities().contains("shell"));
    }

    // ===== isComplex 兼容接口 =====

    @Test
    void isComplex_compatibleWithAnalyze() {
        String text = "创建项目，编写代码，测试验证，部署上线";
        assertEquals(detector.analyze(text).isComplex(), detector.isComplex(text));
    }

    @Test
    void isComplex_simpleTask_returnsFalse() {
        assertFalse(detector.isComplex("你好"));
    }
}
