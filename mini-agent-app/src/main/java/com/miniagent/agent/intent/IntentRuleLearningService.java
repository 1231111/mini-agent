package com.miniagent.agent.intent;

import com.miniagent.config.entity.IntentRuleFeedback;
import com.miniagent.config.entity.IntentRuleHitLog;
import com.miniagent.config.entity.IntentRuleProposal;
import com.miniagent.config.entity.IntentRuleSet;
import com.miniagent.config.repository.IntentRuleFeedbackRepository;
import com.miniagent.config.repository.IntentRuleHitLogRepository;
import com.miniagent.config.repository.IntentRuleProposalRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * L0 人在环学习：反馈 → 规则提案 → 审批发布新 rule_set → 热加载。
 */
@Slf4j
@Service
public class IntentRuleLearningService {

    @Autowired private IntentRuleFeedbackRepository feedbackRepo;
    @Autowired private IntentRuleProposalRepository proposalRepo;
    @Autowired private IntentRuleHitLogRepository hitLogRepo;
    @Autowired private IntentRuleRuntime runtime;

    @Transactional
    public IntentRuleFeedback submitFeedback(Long userId, String executionId, String sessionId,
                                             String correctIntent, String feedbackType,
                                             String note, String userText) {
        String predicted = null;
        String text = userText;
        if (StringUtils.isNotBlank(executionId)) {
            List<IntentRuleHitLog> hits = hitLogRepo.findByExecutionIdOrderByIdAsc(executionId);
            if (!hits.isEmpty()) {
                IntentRuleHitLog last = hits.get(hits.size() - 1);
                predicted = last.getIntent();
                if (StringUtils.isBlank(text)) text = last.getUserText();
                if (StringUtils.isBlank(sessionId)) sessionId = last.getSessionId();
            }
        }
        IntentRuleFeedback fb = new IntentRuleFeedback();
        fb.setExecutionId(executionId);
        fb.setSessionId(sessionId);
        fb.setPredictedIntent(predicted);
        fb.setCorrectIntent(StringUtils.isBlank(correctIntent) ? "NEW_TASK" : correctIntent.trim().toUpperCase());
        fb.setFeedbackType(StringUtils.isBlank(feedbackType) ? "WRONG_INTENT" : feedbackType);
        fb.setNote(note);
        fb.setUserText(text);
        fb.setCreatedBy(userId);
        fb.setStatus(IntentRuleFeedback.STATUS_OPEN);
        fb = feedbackRepo.save(fb);

        IntentRuleProposal proposal = autoPropose(fb);
        fb.setProposalId(proposal.getId());
        fb.setStatus(IntentRuleFeedback.STATUS_PROPOSED);
        return feedbackRepo.save(fb);
    }

    @Transactional
    public IntentRuleProposal autoPropose(IntentRuleFeedback fb) {
        String group = signalGroupForIntent(fb.getCorrectIntent(), fb.getFeedbackType());
        String pattern = suggestPattern(fb.getUserText());
        IntentRuleProposal p = new IntentRuleProposal();
        p.setFeedbackId(fb.getId());
        p.setStatus(IntentRuleProposal.STATUS_PENDING);
        p.setSignalGroup(group);
        p.setPattern(pattern);
        p.setRationale("from feedback#" + fb.getId()
                + " predicted=" + fb.getPredictedIntent()
                + " correct=" + fb.getCorrectIntent()
                + (StringUtils.isBlank(fb.getNote()) ? "" : (" note=" + fb.getNote())));
        return proposalRepo.save(p);
    }

    @Transactional
    public IntentRuleSet approve(Long proposalId, Long reviewerId) {
        IntentRuleProposal p = proposalRepo.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("proposal not found: " + proposalId));
        if (!IntentRuleProposal.STATUS_PENDING.equals(p.getStatus())) {
            throw new IllegalStateException("proposal not PENDING: " + p.getStatus());
        }
        try {
            Pattern.compile(p.getPattern());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid regex: " + e.getMessage());
        }
        IntentRuleSet published = runtime.publishNewRule(
                p.getSignalGroup(), p.getPattern(),
                "approved proposal#" + p.getId(), "LEARNED");
        p.setStatus(IntentRuleProposal.STATUS_APPROVED);
        p.setReviewedBy(reviewerId);
        p.setReviewedAt(LocalDateTime.now());
        p.setPublishedRuleSetId(published.getId());
        proposalRepo.save(p);
        if (Objects.nonNull(p.getFeedbackId())) {
            feedbackRepo.findById(p.getFeedbackId()).ifPresent(fb -> {
                fb.setStatus(IntentRuleFeedback.STATUS_RESOLVED);
                feedbackRepo.save(fb);
            });
        }
        log.info("L0 提案已发布: proposalId={}, ruleSetId=v{}", proposalId, published.getVersion());
        return published;
    }

    @Transactional
    public IntentRuleProposal reject(Long proposalId, Long reviewerId, String reason) {
        IntentRuleProposal p = proposalRepo.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("proposal not found: " + proposalId));
        p.setStatus(IntentRuleProposal.STATUS_REJECTED);
        p.setReviewedBy(reviewerId);
        p.setReviewedAt(LocalDateTime.now());
        if (StringUtils.isNotBlank(reason)) {
            p.setRationale((Objects.isNull(p.getRationale()) ? "" : p.getRationale() + " | ") + "reject: " + reason);
        }
        return proposalRepo.save(p);
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runtime", runtime.activeSnapshot());
        m.put("openFeedback", feedbackRepo.findByStatusOrderByIdDesc(IntentRuleFeedback.STATUS_OPEN).size());
        m.put("pendingProposals", proposalRepo.findByStatusOrderByIdDesc(IntentRuleProposal.STATUS_PENDING).size());
        m.put("recentFeedback", feedbackRepo.findTop50ByOrderByIdDesc());
        m.put("recentProposals", proposalRepo.findTop50ByOrderByIdDesc());
        return m;
    }

    /** 从用户文本抽安全正则：quote 关键片段，避免注入坏 pattern */
    static String suggestPattern(String userText) {
        if (StringUtils.isBlank(userText)) {
            return "(?i)NEED_MANUAL_PATTERN";
        }
        String t = userText.trim().replaceAll("\\s+", " ");
        if (t.length() > 48) t = t.substring(0, 48);
        // 优先取中文/英文关键词块
        String phrase = t;
        var m = Pattern.compile("[\\p{IsHan}]{2,16}|[A-Za-z][A-Za-z0-9_\\-]{2,24}").matcher(t);
        if (m.find()) phrase = m.group();
        return "(?i)" + Pattern.quote(phrase);
    }

    static String signalGroupForIntent(String correctIntent, String feedbackType) {
        if ("MISSED_RULE".equalsIgnoreCase(feedbackType) || "WRONG_INTENT".equalsIgnoreCase(feedbackType)) {
            String intent = Objects.isNull(correctIntent) ? "" : correctIntent.toUpperCase();
            return switch (intent) {
                case "QUESTION" -> IntentRuleRuntime.GROUP_QUESTION;
                case "IMAGE_GENERATION" -> IntentRuleRuntime.GROUP_PURE_IMAGE;
                case "CONTINUE_TASK" -> IntentRuleRuntime.GROUP_CONTINUE;
                case "REVIEW" -> IntentRuleRuntime.GROUP_QUESTION;
                default -> IntentRuleRuntime.GROUP_COMPLEX;
            };
        }
        return IntentRuleRuntime.GROUP_COMPLEX;
    }
}
