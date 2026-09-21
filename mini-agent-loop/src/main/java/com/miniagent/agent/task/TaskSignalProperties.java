package com.miniagent.agent.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * agent.task-signals.* — 只放「文本事实」的正则词表与长度阈值。
 *
 * <p>这里没有任何类别、置信度、模型端点或校准参数。词表命中与否是可复核的事实，
 * 匹配结果直接作为事实交给下游消费，中间不再经一个枚举中转。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.task-signals")
public class TaskSignalProperties {

    private Rules rules = new Rules();

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = Optional.ofNullable(rules).orElse(new Rules());
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
        private int questionMaxLen = 80;
        private boolean forceFullOnImageIntoDoc = true;

        public List<String> getWebSignals() {
            return webSignals;
        }

        public void setWebSignals(List<String> webSignals) {
            this.webSignals = Optional.ofNullable(webSignals).orElse(new ArrayList<>());
        }

        public List<String> getFileSignals() {
            return fileSignals;
        }

        public void setFileSignals(List<String> fileSignals) {
            this.fileSignals = Optional.ofNullable(fileSignals).orElse(new ArrayList<>());
        }

        public List<String> getImageIntoDocSignals() {
            return imageIntoDocSignals;
        }

        public void setImageIntoDocSignals(List<String> imageIntoDocSignals) {
            this.imageIntoDocSignals = Optional.ofNullable(imageIntoDocSignals).orElse(new ArrayList<>());
        }

        public List<String> getPureImageSignals() {
            return pureImageSignals;
        }

        public void setPureImageSignals(List<String> pureImageSignals) {
            this.pureImageSignals = Optional.ofNullable(pureImageSignals).orElse(new ArrayList<>());
        }

        public List<String> getQuestionSignals() {
            return questionSignals;
        }

        public void setQuestionSignals(List<String> questionSignals) {
            this.questionSignals = Optional.ofNullable(questionSignals).orElse(new ArrayList<>());
        }

        public List<String> getTaskActionSignals() {
            return taskActionSignals;
        }

        public void setTaskActionSignals(List<String> taskActionSignals) {
            this.taskActionSignals = Optional.ofNullable(taskActionSignals).orElse(new ArrayList<>());
        }

        public List<String> getContinueSignals() {
            return continueSignals;
        }

        public void setContinueSignals(List<String> continueSignals) {
            this.continueSignals = Optional.ofNullable(continueSignals).orElse(new ArrayList<>());
        }

        public List<String> getComplexSignals() {
            return complexSignals;
        }

        public void setComplexSignals(List<String> complexSignals) {
            this.complexSignals = Optional.ofNullable(complexSignals).orElse(new ArrayList<>());
        }

        public List<String> getImageAndDocSignals() {
            return imageAndDocSignals;
        }

        public void setImageAndDocSignals(List<String> imageAndDocSignals) {
            this.imageAndDocSignals = Optional.ofNullable(imageAndDocSignals).orElse(new ArrayList<>());
        }

        public int getQuestionMaxLen() {
            return questionMaxLen;
        }

        public void setQuestionMaxLen(int questionMaxLen) {
            this.questionMaxLen = questionMaxLen;
        }

        public boolean isForceFullOnImageIntoDoc() {
            return forceFullOnImageIntoDoc;
        }

        public void setForceFullOnImageIntoDoc(boolean forceFullOnImageIntoDoc) {
            this.forceFullOnImageIntoDoc = forceFullOnImageIntoDoc;
        }
    }
}
