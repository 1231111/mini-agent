package com.miniagent.agent.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * agent.intent.* — 模型端点、置信度、L0 信号与工具面均配置化，避免场景写死在代码里。
 */
@Component
@ConfigurationProperties(prefix = "agent.intent")
public class IntentProperties {

    private boolean llmEnabled = true;
    private double minConfidence = 0.65;
    private double clarifyConfidence = 0.45;
    private double rejectConfidence = 0.20;
    private double alternativeMargin = 0.10;
    private int historyMaxMessages = 8;
    private int historyMaxChars = 4000;
    private int timeoutSeconds = 60;
    private int circuitFailureThreshold = 2;
    private int circuitCooldownSeconds = 30;
    private boolean structuredOutputEnabled = true;
    private String modelName = "";
    private String baseUrl = "";
    private String apiKey = "";
    private String classifierSystemPrompt = "";
    private Calibration calibration = new Calibration();
    private Rollout rollout = new Rollout();
    private Rules rules = new Rules();
    private ToolProfiles toolProfiles = new ToolProfiles();

    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public double getClarifyConfidence() { return clarifyConfidence; }
    public void setClarifyConfidence(double clarifyConfidence) { this.clarifyConfidence = clarifyConfidence; }
    public double getRejectConfidence() { return rejectConfidence; }
    public void setRejectConfidence(double rejectConfidence) { this.rejectConfidence = rejectConfidence; }
    public double getAlternativeMargin() { return alternativeMargin; }
    public void setAlternativeMargin(double alternativeMargin) { this.alternativeMargin = alternativeMargin; }
    public int getHistoryMaxMessages() { return historyMaxMessages; }
    public void setHistoryMaxMessages(int historyMaxMessages) { this.historyMaxMessages = historyMaxMessages; }
    public int getHistoryMaxChars() { return historyMaxChars; }
    public void setHistoryMaxChars(int historyMaxChars) { this.historyMaxChars = historyMaxChars; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }
    public int getCircuitCooldownSeconds() { return circuitCooldownSeconds; }
    public void setCircuitCooldownSeconds(int circuitCooldownSeconds) {
        this.circuitCooldownSeconds = circuitCooldownSeconds;
    }
    public boolean isStructuredOutputEnabled() { return structuredOutputEnabled; }
    public void setStructuredOutputEnabled(boolean structuredOutputEnabled) {
        this.structuredOutputEnabled = structuredOutputEnabled;
    }
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
    public Calibration getCalibration() { return calibration; }
    public void setCalibration(Calibration calibration) {
        this.calibration = Optional.ofNullable(calibration).orElse(new Calibration());
    }
    public Rollout getRollout() { return rollout; }
    public void setRollout(Rollout rollout) {
        this.rollout = Optional.ofNullable(rollout).orElse(new Rollout());
    }
    public Rules getRules() { return rules; }
    public void setRules(Rules rules) { this.rules = Optional.ofNullable(rules).orElse(new Rules()); }
    public ToolProfiles getToolProfiles() { return toolProfiles; }
    public void setToolProfiles(ToolProfiles toolProfiles) {
        this.toolProfiles = Optional.ofNullable(toolProfiles).orElse(new ToolProfiles());
    }

    public static class Calibration {
        private boolean enabled = true;
        private double temperature = 1.0;
        private double bias = 0.0;
        private double dedicatedModelBias = 0.0;
        private double fallbackModelBias = -0.10;
        private double ruleBias = 0.35;
        private double heuristicBias = -0.15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public double getBias() { return bias; }
        public void setBias(double bias) { this.bias = bias; }
        public double getDedicatedModelBias() { return dedicatedModelBias; }
        public void setDedicatedModelBias(double dedicatedModelBias) {
            this.dedicatedModelBias = dedicatedModelBias;
        }
        public double getFallbackModelBias() { return fallbackModelBias; }
        public void setFallbackModelBias(double fallbackModelBias) {
            this.fallbackModelBias = fallbackModelBias;
        }
        public double getRuleBias() { return ruleBias; }
        public void setRuleBias(double ruleBias) { this.ruleBias = ruleBias; }
        public double getHeuristicBias() { return heuristicBias; }
        public void setHeuristicBias(double heuristicBias) { this.heuristicBias = heuristicBias; }
    }

    public static class Rollout {
        private String regressionResource = "classpath:intent/intent-regression.jsonl";
        private double minPrecision = 0.95;
        private double maxRegressionRate = 0.02;
        private int shadowMinObservations = 20;
        private int canaryPercent = 10;
        private int canaryMinObservations = 50;
        private double maxDisagreementRate = 0.05;

        public String getRegressionResource() { return regressionResource; }
        public void setRegressionResource(String regressionResource) {
            this.regressionResource = regressionResource;
        }
        public double getMinPrecision() { return minPrecision; }
        public void setMinPrecision(double minPrecision) { this.minPrecision = minPrecision; }
        public double getMaxRegressionRate() { return maxRegressionRate; }
        public void setMaxRegressionRate(double maxRegressionRate) {
            this.maxRegressionRate = maxRegressionRate;
        }
        public int getShadowMinObservations() { return shadowMinObservations; }
        public void setShadowMinObservations(int shadowMinObservations) {
            this.shadowMinObservations = shadowMinObservations;
        }
        public int getCanaryPercent() { return canaryPercent; }
        public void setCanaryPercent(int canaryPercent) { this.canaryPercent = canaryPercent; }
        public int getCanaryMinObservations() { return canaryMinObservations; }
        public void setCanaryMinObservations(int canaryMinObservations) {
            this.canaryMinObservations = canaryMinObservations;
        }
        public double getMaxDisagreementRate() { return maxDisagreementRate; }
        public void setMaxDisagreementRate(double maxDisagreementRate) {
            this.maxDisagreementRate = maxDisagreementRate;
        }
    }

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
        private boolean forceFullOnWebAndFile = true;
        private boolean forceFullOnImageIntoDoc = true;

        public List<String> getWebSignals() { return webSignals; }
        public void setWebSignals(List<String> webSignals) {
            this.webSignals = Optional.ofNullable(webSignals).orElse(new ArrayList<>());
        }
        public List<String> getFileSignals() { return fileSignals; }
        public void setFileSignals(List<String> fileSignals) {
            this.fileSignals = Optional.ofNullable(fileSignals).orElse(new ArrayList<>());
        }
        public List<String> getImageIntoDocSignals() { return imageIntoDocSignals; }
        public void setImageIntoDocSignals(List<String> imageIntoDocSignals) {
            this.imageIntoDocSignals = Optional.ofNullable(imageIntoDocSignals).orElse(new ArrayList<>());
        }
        public List<String> getPureImageSignals() { return pureImageSignals; }
        public void setPureImageSignals(List<String> pureImageSignals) {
            this.pureImageSignals = Optional.ofNullable(pureImageSignals).orElse(new ArrayList<>());
        }
        public List<String> getQuestionSignals() { return questionSignals; }
        public void setQuestionSignals(List<String> questionSignals) {
            this.questionSignals = Optional.ofNullable(questionSignals).orElse(new ArrayList<>());
        }
        public List<String> getTaskActionSignals() { return taskActionSignals; }
        public void setTaskActionSignals(List<String> taskActionSignals) {
            this.taskActionSignals = Optional.ofNullable(taskActionSignals).orElse(new ArrayList<>());
        }
        public List<String> getContinueSignals() { return continueSignals; }
        public void setContinueSignals(List<String> continueSignals) {
            this.continueSignals = Optional.ofNullable(continueSignals).orElse(new ArrayList<>());
        }
        public List<String> getComplexSignals() { return complexSignals; }
        public void setComplexSignals(List<String> complexSignals) {
            this.complexSignals = Optional.ofNullable(complexSignals).orElse(new ArrayList<>());
        }
        public List<String> getImageAndDocSignals() { return imageAndDocSignals; }
        public void setImageAndDocSignals(List<String> imageAndDocSignals) {
            this.imageAndDocSignals = Optional.ofNullable(imageAndDocSignals).orElse(new ArrayList<>());
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

    public static class ToolProfiles {
        private List<String> full = null;
        private List<String> image = new ArrayList<>();
        private List<String> question = new ArrayList<>();

        public List<String> getFull() { return full; }
        public void setFull(List<String> full) { this.full = full; }
        public List<String> getImage() { return image; }
        public void setImage(List<String> image) {
            this.image = Optional.ofNullable(image).orElse(new ArrayList<>());
        }
        public List<String> getQuestion() { return question; }
        public void setQuestion(List<String> question) {
            this.question = Optional.ofNullable(question).orElse(new ArrayList<>());
        }
    }
}
