package com.miniagent.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.agent.trace.TraceStepType;
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
            if (!TraceStepType.isKnownPersisted(stepType)) {
                stepType = TraceStepType.INTENT_L2.name();
            }
            traceRecorder.recordNode(stepType, planJson, "SUCCESS", durationMs);
            traceRecorder.recordNode(TraceStepType.TASK_PLAN.name(), planJson, "SUCCESS", 0);
        } catch (Exception e) {
            log.warn("意图命中日志写入失败: {}", e.getMessage());
        }
    }

    /** 漏斗走过但未定案（如 L0 规则未命中、L1 低置信），只写 Trace，不写 hit 表定案行 */
    public void recordSkip(String layer, String why) {
        try {
            String stepType = "INTENT_" + (layer == null ? "L2" : layer.trim().toUpperCase());
            if (!TraceStepType.isKnownPersisted(stepType)) return;
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
