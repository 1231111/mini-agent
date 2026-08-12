package com.miniagent.web;

import com.miniagent.agent.intent.IntentRuleLearningService;
import com.miniagent.agent.intent.IntentRuleRuntime;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.config.entity.IntentRule;
import com.miniagent.config.entity.IntentRuleFeedback;
import com.miniagent.config.entity.IntentRuleProposal;
import com.miniagent.config.entity.IntentRuleSet;
import com.miniagent.config.repository.IntentRuleFeedbackRepository;
import com.miniagent.config.repository.IntentRuleHitLogRepository;
import com.miniagent.config.repository.IntentRuleProposalRepository;
import com.miniagent.config.repository.IntentRuleRepository;
import com.miniagent.config.repository.IntentRuleSetRepository;
import com.miniagent.config.security.SessionCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * L0 规则运维 API：查看 ACTIVE、反馈、提案审批、热加载。
 */
@RestController
@RequestMapping("/api/intent")
public class IntentRuleAdminController {

    @Autowired private IntentRuleRuntime runtime;
    @Autowired private IntentRuleLearningService learning;
    @Autowired private IntentRuleSetRepository ruleSetRepo;
    @Autowired private IntentRuleRepository ruleRepo;
    @Autowired private IntentRuleFeedbackRepository feedbackRepo;
    @Autowired private IntentRuleProposalRepository proposalRepo;
    @Autowired private IntentRuleHitLogRepository hitLogRepo;
    @GetMapping(value = "/rules/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> activeRules(HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        Map<String, Object> out = new LinkedHashMap<>(runtime.activeSnapshot());
        ruleSetRepo.findFirstByStatusOrderByVersionDesc(IntentRuleSet.STATUS_ACTIVE).ifPresent(set -> {
            out.put("status", set.getStatus());
            out.put("source", set.getSource());
            out.put("note", set.getNote());
            out.put("configJson", set.getConfigJson());
            List<IntentRule> rules = ruleRepo.findByRuleSetIdOrderByPriorityAscIdAsc(set.getId());
            out.put("rules", rules);
        });
        return ApiResponse.ok(out);
    }

    @PostMapping(value = "/rules/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> reload(HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        runtime.reload();
        return ApiResponse.ok(Map.of("runtime", runtime.activeSnapshot()));
    }

    @GetMapping(value = "/hits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> hits(@RequestParam(required = false) String executionId, HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (StringUtils.isNotBlank(executionId)) {
            return ApiResponse.ok(hitLogRepo.findByExecutionIdOrderByIdAsc(executionId));
        }
        return ApiResponse.ok(hitLogRepo.findTop50ByOrderByIdDesc());
    }

    @GetMapping(value = "/learning", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> learningDashboard(HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        return ApiResponse.ok(learning.dashboard());
    }

    @PostMapping(value = "/feedback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> feedback(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (Objects.isNull(body)) return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "body required");
        try {
            IntentRuleFeedback fb = learning.submitFeedback(
                    userId,
                    str(body.get("executionId")),
                    str(body.get("sessionId")),
                    str(body.get("correctIntent")),
                    str(body.get("feedbackType")),
                    str(body.get("note")),
                    str(body.get("userText")));
            return ApiResponse.ok(Map.of("feedbackId", fb.getId(),
                    "proposalId", Objects.requireNonNullElse(fb.getProposalId(), 0L),
                    "status", fb.getStatus()));
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.INTENT_CLASSIFY_FAILED, Objects.requireNonNullElse(e.getMessage(), "failed"));
        }
    }

    @GetMapping(value = "/proposals", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> proposals(@RequestParam(defaultValue = "PENDING") String status,
                                          HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if ("ALL".equalsIgnoreCase(status)) return ApiResponse.ok(proposalRepo.findTop50ByOrderByIdDesc());
        return ApiResponse.ok(proposalRepo.findByStatusOrderByIdDesc(status.toUpperCase()));
    }

    @PostMapping(value = "/proposals/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> approve(@PathVariable("id") Long id, HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        try {
            IntentRuleSet set = learning.approve(id, userId);
            return ApiResponse.ok(Map.of("ruleSetId", set.getId(), "version", set.getVersion(),
                    "runtime", runtime.activeSnapshot()));
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.INTENT_CLASSIFY_FAILED, Objects.requireNonNullElse(e.getMessage(), "failed"));
        }
    }

    @PostMapping(value = "/proposals/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> reject(@PathVariable("id") Long id,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        Long userId = uid(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        try {
            String reason = Objects.isNull(body) ? null : str(body.get("reason"));
            IntentRuleProposal p = learning.reject(id, userId, reason);
            return ApiResponse.ok(Map.of("status", p.getStatus()));
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.INTENT_CLASSIFY_FAILED, Objects.requireNonNullElse(e.getMessage(), "failed"));
        }
    }

    private Long uid(HttpServletRequest request) {
        return SessionCookieService.userIdFromRequest(request);
    }

    private static String str(Object v) {
        if (Objects.isNull(v)) return null;
        String s = String.valueOf(v);
        return "null".equals(s) ? null : s;
    }
}
