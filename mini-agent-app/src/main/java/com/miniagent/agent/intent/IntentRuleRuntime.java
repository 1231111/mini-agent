package com.miniagent.agent.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.entity.IntentRule;
import com.miniagent.config.entity.IntentRuleSet;
import com.miniagent.config.entity.IntentToolProfile;
import com.miniagent.config.repository.IntentRuleRepository;
import com.miniagent.config.repository.IntentRuleSetRepository;
import com.miniagent.config.repository.IntentToolProfileRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * L0 规则运行时：MySQL ACTIVE rule_set → IntentProperties → IntentSignalMatcher 热编译。
 * YAML 仅作空库 bootstrap。
 */
@Slf4j
@Service
@Order(100)
public class IntentRuleRuntime {

    public static final String GROUP_WEB = "WEB";
    public static final String GROUP_FILE = "FILE";
    public static final String GROUP_IMAGE_INTO_DOC = "IMAGE_INTO_DOC";
    public static final String GROUP_PURE_IMAGE = "PURE_IMAGE";
    public static final String GROUP_QUESTION = "QUESTION";
    public static final String GROUP_TASK_ACTION = "TASK_ACTION";
    public static final String GROUP_CONTINUE = "CONTINUE";
    public static final String GROUP_COMPLEX = "COMPLEX";
    public static final String GROUP_IMAGE_AND_DOC = "IMAGE_AND_DOC";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private IntentProperties props;
    @Autowired
    private IntentSignalMatcher signals;
    @Autowired
    private IntentRuleSetRepository ruleSetRepo;
    @Autowired
    private IntentRuleRepository ruleRepo;
    @Autowired
    private IntentToolProfileRepository toolProfileRepo;

    private volatile Long activeRuleSetId;
    private volatile int activeVersion;

    @PostConstruct
    public void init() {
        try {
            ensureBootstrapped();
            reload();
        } catch (Exception e) {
            log.error("IntentRuleRuntime 初始化失败，回退 YAML: {}", e.getMessage());
        }
    }

    public Long activeRuleSetId() { return activeRuleSetId; }
    public int activeVersion() { return activeVersion; }

    @Transactional
    public synchronized void ensureBootstrapped() {
        if (ruleSetRepo.findFirstByStatusOrderByVersionDesc(IntentRuleSet.STATUS_ACTIVE).isPresent()) {
            return;
        }
        IntentRuleSet set = new IntentRuleSet();
        set.setVersion(1);
        set.setStatus(IntentRuleSet.STATUS_ACTIVE);
        set.setSource("YAML_BOOTSTRAP");
        set.setNote("bootstrap from application.yml agent.intent.rules");
        set.setConfigJson(configFromProps(props.getRules()));
        set.setActivatedAt(LocalDateTime.now());
        set = ruleSetRepo.save(set);

        IntentProperties.Rules r = props.getRules();
        savePatterns(set.getId(), GROUP_WEB, r.getWebSignals());
        savePatterns(set.getId(), GROUP_FILE, r.getFileSignals());
        savePatterns(set.getId(), GROUP_IMAGE_INTO_DOC, r.getImageIntoDocSignals());
        savePatterns(set.getId(), GROUP_PURE_IMAGE, r.getPureImageSignals());
        savePatterns(set.getId(), GROUP_QUESTION, r.getQuestionSignals());
        savePatterns(set.getId(), GROUP_TASK_ACTION, r.getTaskActionSignals());
        savePatterns(set.getId(), GROUP_CONTINUE, r.getContinueSignals());
        savePatterns(set.getId(), GROUP_COMPLEX, r.getComplexSignals());
        savePatterns(set.getId(), GROUP_IMAGE_AND_DOC, r.getImageAndDocSignals());

        IntentProperties.ToolProfiles tp = props.getToolProfiles();
        saveProfile(set.getId(), "FULL", tp.getFull());
        saveProfile(set.getId(), "IMAGE", tp.getImage());
        saveProfile(set.getId(), "QUESTION", tp.getQuestion());

        log.info("Intent L0 已从 YAML bootstrap 到 MySQL: ruleSetId={}, version=1", set.getId());
    }

    @Transactional(readOnly = true)
    public synchronized void reload() {
        Optional<IntentRuleSet> active = ruleSetRepo.findFirstByStatusOrderByVersionDesc(IntentRuleSet.STATUS_ACTIVE);
        if (active.isEmpty()) {
            log.warn("无 ACTIVE intent_rule_set，继续使用当前内存规则");
            return;
        }
        IntentRuleSet set = active.get();
        List<IntentRule> rules = ruleRepo.findByRuleSetIdAndEnabledTrueOrderByPriorityAscIdAsc(set.getId());
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (IntentRule rule : rules) {
            byGroup.computeIfAbsent(rule.getSignalGroup(), k -> new ArrayList<>()).add(rule.getPattern());
        }
        IntentProperties.Rules r = props.getRules();
        r.setWebSignals(byGroup.getOrDefault(GROUP_WEB, List.of()));
        r.setFileSignals(byGroup.getOrDefault(GROUP_FILE, List.of()));
        r.setImageIntoDocSignals(byGroup.getOrDefault(GROUP_IMAGE_INTO_DOC, List.of()));
        r.setPureImageSignals(byGroup.getOrDefault(GROUP_PURE_IMAGE, List.of()));
        r.setQuestionSignals(byGroup.getOrDefault(GROUP_QUESTION, List.of()));
        r.setTaskActionSignals(byGroup.getOrDefault(GROUP_TASK_ACTION, List.of()));
        r.setContinueSignals(byGroup.getOrDefault(GROUP_CONTINUE, List.of()));
        r.setComplexSignals(byGroup.getOrDefault(GROUP_COMPLEX, List.of()));
        r.setImageAndDocSignals(byGroup.getOrDefault(GROUP_IMAGE_AND_DOC, List.of()));
        applyConfigJson(r, set.getConfigJson());

        for (IntentToolProfile p : toolProfileRepo.findByRuleSetId(set.getId())) {
            List<String> tools = parseTools(p.getToolsJson());
            switch (Optional.ofNullable(p.getProfile()).orElse("").toUpperCase()) {
                case "FULL" -> props.getToolProfiles().setFull(tools);
                case "IMAGE" -> props.getToolProfiles().setImage(tools == null ? List.of() : tools);
                case "QUESTION" -> props.getToolProfiles().setQuestion(tools == null ? List.of() : tools);
                default -> {}
            }
        }

        signals.reload();
        activeRuleSetId = set.getId();
        activeVersion = set.getVersion();
        log.info("Intent L0 已从 MySQL 加载: ruleSetId={}, version={}, rules={}",
                activeRuleSetId, activeVersion, rules.size());
    }

    /** 克隆 ACTIVE 并追加一条规则，发布为新 ACTIVE（人审通过后调用） */
    @Transactional
    public synchronized IntentRuleSet publishNewRule(String signalGroup, String pattern, String note, String source) {
        IntentRuleSet current = ruleSetRepo.findFirstByStatusOrderByVersionDesc(IntentRuleSet.STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalStateException("无 ACTIVE rule_set"));
        List<IntentRule> oldRules = ruleRepo.findByRuleSetIdOrderByPriorityAscIdAsc(current.getId());
        List<IntentToolProfile> oldProfiles = toolProfileRepo.findByRuleSetId(current.getId());

        current.setStatus(IntentRuleSet.STATUS_ARCHIVED);
        ruleSetRepo.save(current);

        IntentRuleSet next = new IntentRuleSet();
        next.setVersion(current.getVersion() + 1);
        next.setStatus(IntentRuleSet.STATUS_ACTIVE);
        next.setSource(StringUtils.isBlank(source) ? "LEARNED" : source);
        next.setNote(note);
        next.setConfigJson(current.getConfigJson());
        next.setActivatedAt(LocalDateTime.now());
        next = ruleSetRepo.save(next);

        for (IntentRule old : oldRules) {
            IntentRule copy = new IntentRule();
            copy.setRuleSetId(next.getId());
            copy.setSignalGroup(old.getSignalGroup());
            copy.setPattern(old.getPattern());
            copy.setEnabled(old.isEnabled());
            copy.setPriority(old.getPriority());
            copy.setNote(old.getNote());
            ruleRepo.save(copy);
        }
        IntentRule added = new IntentRule();
        added.setRuleSetId(next.getId());
        added.setSignalGroup(signalGroup);
        added.setPattern(pattern);
        added.setEnabled(true);
        added.setPriority(50);
        added.setNote(note);
        ruleRepo.save(added);

        for (IntentToolProfile old : oldProfiles) {
            IntentToolProfile copy = new IntentToolProfile();
            copy.setRuleSetId(next.getId());
            copy.setProfile(old.getProfile());
            copy.setToolsJson(old.getToolsJson());
            toolProfileRepo.save(copy);
        }

        reload();
        return next;
    }

    public Map<String, Object> activeSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleSetId", activeRuleSetId);
        m.put("version", activeVersion);
        IntentProperties.Rules r = props.getRules();
        m.put("webSignals", r.getWebSignals().size());
        m.put("fileSignals", r.getFileSignals().size());
        m.put("imageIntoDocSignals", r.getImageIntoDocSignals().size());
        m.put("pureImageSignals", r.getPureImageSignals().size());
        m.put("questionSignals", r.getQuestionSignals().size());
        m.put("taskActionSignals", r.getTaskActionSignals().size());
        m.put("continueSignals", r.getContinueSignals().size());
        m.put("complexSignals", r.getComplexSignals().size());
        m.put("imageAndDocSignals", r.getImageAndDocSignals().size());
        return m;
    }

    private void savePatterns(Long setId, String group, List<String> patterns) {
        if (patterns == null) return;
        int pri = 100;
        for (String p : patterns) {
            if (StringUtils.isBlank(p)) continue;
            IntentRule rule = new IntentRule();
            rule.setRuleSetId(setId);
            rule.setSignalGroup(group);
            rule.setPattern(p);
            rule.setEnabled(true);
            rule.setPriority(pri++);
            ruleRepo.save(rule);
        }
    }

    private void saveProfile(Long setId, String profile, List<String> tools) {
        IntentToolProfile p = new IntentToolProfile();
        p.setRuleSetId(setId);
        p.setProfile(profile);
        p.setToolsJson(toolsToJson(tools));
        toolProfileRepo.save(p);
    }

    static String configFromProps(IntentProperties.Rules r) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("forceFullOnWebAndFile", r.isForceFullOnWebAndFile());
            m.put("forceFullOnImageIntoDoc", r.isForceFullOnImageIntoDoc());
            m.put("pureImageMaxLen", r.getPureImageMaxLen());
            m.put("questionMaxLen", r.getQuestionMaxLen());
            m.put("reviewMaxLen", r.getReviewMaxLen());
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    static void applyConfigJson(IntentProperties.Rules r, String json) {
        if (StringUtils.isBlank(json)) return;
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<>() {});
            if (m.get("forceFullOnWebAndFile") instanceof Boolean b) r.setForceFullOnWebAndFile(b);
            if (m.get("forceFullOnImageIntoDoc") instanceof Boolean b) r.setForceFullOnImageIntoDoc(b);
            if (m.get("pureImageMaxLen") instanceof Number n) r.setPureImageMaxLen(n.intValue());
            if (m.get("questionMaxLen") instanceof Number n) r.setQuestionMaxLen(n.intValue());
            if (m.get("reviewMaxLen") instanceof Number n) r.setReviewMaxLen(n.intValue());
        } catch (Exception ignored) {
        }
    }

    static String toolsToJson(List<String> tools) {
        try {
            return JSON.writeValueAsString(tools);
        } catch (Exception e) {
            return "[]";
        }
    }

    static List<String> parseTools(String json) {
        if (json == null) return null;
        if (StringUtils.isBlank(json)) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
