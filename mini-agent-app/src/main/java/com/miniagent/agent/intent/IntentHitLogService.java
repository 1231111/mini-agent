package com.miniagent.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.RunStatus;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.config.entity.IntentRuleHitLog;
import com.miniagent.config.repository.IntentRuleHitLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class IntentHitLogService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private IntentRuleHitLogRepository repo;
    @Autowired private IntentRuleRuntime runtime;
    @Autowired private IntentSignalMatcher signals;
    @Autowired private TraceRecorder traceRecorder;

    /** 意图识别阶段开始（独立功能点，与执行计划分离） */
    public void begin(String userText, boolean hasImage) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", "INTENT");
            m.put("hasImage", hasImage);
            m.put("question", truncate(userText, 500));
            m.put("pipeline", "L0规则→L1小模型→L2启发式");
            traceRecorder.recordNode(AgentStepNode.INTENT_START.name(),
                    JSON.writeValueAsString(m), RunStatus.RUNNING.name(), 0);
        } catch (Exception e) {
            log.warn("意图识别开始节点写入失败: {}", e.getMessage());
        }
    }

    public void record(String layer, String userText, TaskPlan plan,
                       LlmIntentClassifier.Classification c, long durationMs) {
        if (Objects.isNull(plan)) return;
        try {
            Map<String, Object> matched = signals.describeMatches(userText);
            String planJson = toPlanJson(plan, c, matched);
            IntentRuleHitLog row = new IntentRuleHitLog();
            row.setExecutionId(traceRecorder.currentExecutionId());
            row.setSessionId(traceRecorder.currentSessionId());
            row.setRuleSetId(runtime.activeRuleSetId());
            row.setLayer(layer);
            row.setIntent(plan.intent().name());
            row.setReason(plan.reason());
            row.setUserText(truncate(userText, 2000));
            row.setMatchedSignals(JSON.writeValueAsString(matched));
            row.setPlanJson(planJson);
            repo.save(row);

            String stepType = "INTENT_" + (layer == null ? "L2" : layer.trim().toUpperCase());
            if (!AgentStepNode.isKnownPersisted(stepType)) {
                stepType = AgentStepNode.INTENT_L2.name();
            }
            // 1) 漏斗定案层  2) 意图识别阶段结束  3) 下游执行计划快照
            traceRecorder.recordNode(stepType, planJson, RunStatus.SUCCESS.name(), durationMs);
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("phase", "INTENT");
            end.put("decidedLayer", layer);
            end.put("intent", plan.intent().name());
            end.put("taskGoal", plan.taskGoal());
            if (Objects.nonNull(c)) end.put("confidence", c.confidence());
            if (plan.intent() == IntentType.REVIEW) end.put("route", "REVIEW_FAST_PATH");
            traceRecorder.recordNode(AgentStepNode.INTENT_END.name(),
                    JSON.writeValueAsString(end), RunStatus.SUCCESS.name(), durationMs);
            traceRecorder.recordNode(AgentStepNode.TASK_PLAN.name(), planJson, RunStatus.SUCCESS.name(), 0);
        } catch (Exception e) {
            log.warn("意图命中日志写入失败: {}", e.getMessage());
        }
    }

    /** 漏斗走过但未定案（如 L0 规则未命中、L1 低置信），只写 Trace，不写 hit 表定案行 */
    public void recordSkip(String layer, String why) {
        try {
            String stepType = "INTENT_" + (layer == null ? "L2" : layer.trim().toUpperCase());
            if (!AgentStepNode.isKnownPersisted(stepType)) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skipped", true);
            m.put("reason", why == null ? "未命中" : why);
            traceRecorder.recordNode(stepType, JSON.writeValueAsString(m), "SKIPPED", 0);
        } catch (Exception e) {
            log.warn("意图跳过节点写入失败: {}", e.getMessage());
        }
    }

    private static String toPlanJson(TaskPlan plan, LlmIntentClassifier.Classification c,
                                     Map<String, Object> matched) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", plan.intent().name());
            if (plan.intent() == IntentType.REVIEW) {
                m.put("route", "REVIEW_FAST_PATH");
            }
            m.put("taskGoal", plan.taskGoal());
            m.put("reason", plan.reason());
            m.put("requiresStructuredPlan", plan.requiresStructuredPlan());
            m.put("shouldUseHistory", plan.shouldUseHistory());
            m.put("needsTools", plan.needsTools());
            m.put("allowedTools", plan.allowedTools());
            m.put("matchedSignals", matched);
            if (Objects.nonNull(c)) {
                m.put("confidence", c.confidence());
                m.put("toolProfile", c.toolProfile());
                m.put("classifierReason", c.reason());
            }
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"intent\":\"" + plan.intent() + "\"}";
        }
    }

    private static String truncate(String s, int max) {
        if (StringUtils.isBlank(s)) return s;
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }
}
