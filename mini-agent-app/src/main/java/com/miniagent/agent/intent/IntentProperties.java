package com.miniagent.agent.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * agent.intent.* — 模型端点、置信度、L0 信号与工具面均配置化，避免场景写死在代码里。
 */
@Component
@ConfigurationProperties(prefix = "agent.intent")
public class IntentProperties {

    private boolean llmEnabled = true;
    private double minConfidence = 0.65;
    private int historyMaxMessages = 8;
    private int historyMaxChars = 4000;
    private int timeoutSeconds = 60;
    private String modelName = "";
    private String baseUrl = "";
    private String apiKey = "";
    /** L1 分类 system prompt；空则用内置通用模板 */
    private String classifierSystemPrompt = "";
    private Rules rules = new Rules();
    private ToolProfiles toolProfiles = new ToolProfiles();

    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public int getHistoryMaxMessages() { return historyMaxMessages; }
    public void setHistoryMaxMessages(int historyMaxMessages) { this.historyMaxMessages = historyMaxMessages; }
    public int getHistoryMaxChars() { return historyMaxChars; }
    public void setHistoryMaxChars(int historyMaxChars) { this.historyMaxChars = historyMaxChars; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getClassifierSystemPrompt() { return classifierSystemPrompt; }
    public void setClassifierSystemPrompt(String classifierSystemPrompt) {
        this.classifierSystemPrompt = classifierSystemPrompt;
    }
    public Rules getRules() { return rules; }
    public void setRules(Rules rules) { this.rules = rules != null ? rules : new Rules(); }
    public ToolProfiles getToolProfiles() { return toolProfiles; }
    public void setToolProfiles(ToolProfiles toolProfiles) {
        this.toolProfiles = toolProfiles != null ? toolProfiles : new ToolProfiles();
    }

    /** L0/L2 信号：每项为正则；改场景只改配置 */
    public static class Rules {
        private List<String> webSignals = new ArrayList<>();
        private List<String> fileSignals = new ArrayList<>();
        private List<String> imageIntoDocSignals = new ArrayList<>();
        private List<String> pureImageSignals = new ArrayList<>();
        private List<String> questionSignals = new ArrayList<>();
        private List<String> taskActionSignals = new ArrayList<>();
        private List<String> continueSignals = new ArrayList<>();
        private List<String> complexSignals = new ArrayList<>();
        private List<String> imageAndDocSignals = new ArrayList<>();
        private int pureImageMaxLen = 40;
        private int questionMaxLen = 80;
        private int reviewMaxLen = 10;
        /** web∩file 命中时强制 FULL+structured */
        private boolean forceFullOnWebAndFile = true;
        /** image-into-doc 命中时强制 FULL+structured */
        private boolean forceFullOnImageIntoDoc = true;

        public List<String> getWebSignals() { return webSignals; }
        public void setWebSignals(List<String> webSignals) {
            this.webSignals = webSignals != null ? webSignals : new ArrayList<>();
        }
        public List<String> getFileSignals() { return fileSignals; }
        public void setFileSignals(List<String> fileSignals) {
            this.fileSignals = fileSignals != null ? fileSignals : new ArrayList<>();
        }
        public List<String> getImageIntoDocSignals() { return imageIntoDocSignals; }
        public void setImageIntoDocSignals(List<String> imageIntoDocSignals) {
            this.imageIntoDocSignals = imageIntoDocSignals != null ? imageIntoDocSignals : new ArrayList<>();
        }
        public List<String> getPureImageSignals() { return pureImageSignals; }
        public void setPureImageSignals(List<String> pureImageSignals) {
            this.pureImageSignals = pureImageSignals != null ? pureImageSignals : new ArrayList<>();
        }
        public List<String> getQuestionSignals() { return questionSignals; }
        public void setQuestionSignals(List<String> questionSignals) {
            this.questionSignals = questionSignals != null ? questionSignals : new ArrayList<>();
        }
        public List<String> getTaskActionSignals() { return taskActionSignals; }
        public void setTaskActionSignals(List<String> taskActionSignals) {
            this.taskActionSignals = taskActionSignals != null ? taskActionSignals : new ArrayList<>();
        }
        public List<String> getContinueSignals() { return continueSignals; }
        public void setContinueSignals(List<String> continueSignals) {
            this.continueSignals = continueSignals != null ? continueSignals : new ArrayList<>();
        }
        public List<String> getComplexSignals() { return complexSignals; }
        public void setComplexSignals(List<String> complexSignals) {
            this.complexSignals = complexSignals != null ? complexSignals : new ArrayList<>();
        }
        public List<String> getImageAndDocSignals() { return imageAndDocSignals; }
        public void setImageAndDocSignals(List<String> imageAndDocSignals) {
            this.imageAndDocSignals = imageAndDocSignals != null ? imageAndDocSignals : new ArrayList<>();
        }
        public int getPureImageMaxLen() { return pureImageMaxLen; }
        public void setPureImageMaxLen(int pureImageMaxLen) { this.pureImageMaxLen = pureImageMaxLen; }
        public int getQuestionMaxLen() { return questionMaxLen; }
        public void setQuestionMaxLen(int questionMaxLen) { this.questionMaxLen = questionMaxLen; }
        public int getReviewMaxLen() { return reviewMaxLen; }
        public void setReviewMaxLen(int reviewMaxLen) { this.reviewMaxLen = reviewMaxLen; }
        public boolean isForceFullOnWebAndFile() { return forceFullOnWebAndFile; }
        public void setForceFullOnWebAndFile(boolean forceFullOnWebAndFile) {
            this.forceFullOnWebAndFile = forceFullOnWebAndFile;
        }
        public boolean isForceFullOnImageIntoDoc() { return forceFullOnImageIntoDoc; }
        public void setForceFullOnImageIntoDoc(boolean forceFullOnImageIntoDoc) {
            this.forceFullOnImageIntoDoc = forceFullOnImageIntoDoc;
        }
    }

    /**
     * 工具面配置。full 为空/null → 不限制（用注册表全量，含 MCP）。
     */
    public static class ToolProfiles {
        private List<String> full = null;
        private List<String> image = new ArrayList<>();
        private List<String> question = new ArrayList<>();

        public List<String> getFull() { return full; }
        public void setFull(List<String> full) { this.full = full; }
        public List<String> getImage() { return image; }
        public void setImage(List<String> image) {
            this.image = image != null ? image : new ArrayList<>();
        }
        public List<String> getQuestion() { return question; }
        public void setQuestion(List<String> question) {
            this.question = question != null ? question : new ArrayList<>();
        }
    }
}
