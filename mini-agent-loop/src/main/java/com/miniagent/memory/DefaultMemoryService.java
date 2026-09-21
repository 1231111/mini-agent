package com.miniagent.memory;

import com.miniagent.memory.model.AgentContext;
import com.miniagent.memory.model.Episode;
import com.miniagent.memory.model.MemoryContext;
import com.miniagent.memory.model.MemoryReadPolicy;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.SemanticFact;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Blob 作非结构化层；结构化库作事实/情景/工作记忆。检索服从 {@link MemoryReadPolicy}。
 */
@Service
public class DefaultMemoryService implements MemoryService {

    private static final int OVERLAP_MIN_CHARS = 8;
    private static final int FACT_SUBJECT_MAX = 256;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired(required = false)
    private MemoryManager memoryManager;

    @Override
    public String retrieveForPrompt(String sessionId, String query, MemoryReadPolicy policy) {
        if (policy == null || !policy.any()) {
            return "";
        }
        String structured = "";
        if (memoryManager != null && (policy.working() || policy.longTerm() || policy.user())) {
            if (StringUtils.isNotBlank(sessionId)) {
                structured = formatStructured(loadStructured(sessionId, query, policy));
            } else if (policy.user()) {
                structured = formatStructured(userOnlyContext());
            }
        }
        String blob = memoryStore.getSnapshotForQuery(
                query,
                policy.longTerm(),
                policy.user(),
                policy.midterm(),
                policy.userMaxChars());
        blob = dropOverlappingBlobLines(blob, structured);

        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(structured)) {
            parts.add(structured);
        }
        if (StringUtils.isNotBlank(blob)) {
            parts.add(blob);
        }
        return String.join("\n\n", parts);
    }

    @Override
    public Map<String, Object> add(String target, String content) {
        if (MemoryKeys.TARGET_USER.equals(target) && memoryManager != null) {
            return addUserFact(content);
        }
        if (MemoryKeys.TARGET_MEMORY.equals(target) && alreadyInStructured(content)) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("success", true);
            dup.put("target", target);
            dup.put("message", "已在结构化记忆中，未写入非结构化笔记。");
            return dup;
        }
        return memoryStore.add(target, content);
    }

    @Override
    public Map<String, Object> replace(String target, String oldText, String newContent) {
        if (MemoryKeys.TARGET_USER.equals(target) && memoryManager != null) {
            return addUserFact(newContent);
        }
        return memoryStore.replace(target, oldText, newContent);
    }

    @Override
    public Map<String, Object> remove(String target, String oldText) {
        return memoryStore.remove(target, oldText);
    }

    @Override
    public List<String> read(String target) {
        List<String> out = new ArrayList<>(memoryStore.readEntries(target));
        if (MemoryKeys.TARGET_USER.equals(target) && memoryManager != null) {
            for (SemanticFact fact : userFacts()) {
                if (fact.getObjectValue() != null && !out.contains(fact.getObjectValue())) {
                    out.add(fact.getObjectValue());
                }
            }
        }
        return out;
    }

    @Override
    public void promoteUserBlob() {
        if (memoryManager == null) {
            return;
        }
        for (String entry : new ArrayList<>(memoryStore.readEntries(MemoryKeys.TARGET_USER))) {
            if (StringUtils.isBlank(entry)) {
                continue;
            }
            boolean ok = hasUserFact(entry);
            if (!ok) {
                Map<String, Object> written = addUserFact(entry);
                ok = Boolean.TRUE.equals(written.get("success"));
            }
            if (ok) {
                memoryStore.remove(MemoryKeys.TARGET_USER, entry);
            }
        }
    }

    private Map<String, Object> addUserFact(String content) {
        if (StringUtils.isBlank(content)) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("target", MemoryKeys.TARGET_USER);
            fail.put("message", "内容不能为空。");
            return fail;
        }
        content = content.trim();
        String scanErr = SecurityScanner.scan(content);
        if (scanErr != null) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("target", MemoryKeys.TARGET_USER);
            fail.put("message", scanErr);
            return fail;
        }
        SemanticFact fact = new SemanticFact();
        fact.setTenantId(MemoryStore.effectiveTenantId());
        fact.setScope(MemoryScope.ofUser(
                MemoryStore.effectiveTenantId(), MemoryStore.effectiveUserIdString()));
        fact.setSubject(clip(content, FACT_SUBJECT_MAX));
        fact.setPredicate(MemoryKeys.PREDICATE_PREFERENCE);
        fact.setObjectValue(content);
        fact.setSource(MemoryKeys.SOURCE_MEMORY_TOOL);
        memoryManager.writeFact(fact);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("target", MemoryKeys.TARGET_USER);
        ok.put("message", "已写入用户偏好（语义事实，未重复写入画像 blob）。");
        return ok;
    }

    private boolean alreadyInStructured(String content) {
        if (memoryManager == null || StringUtils.isBlank(content)) {
            return false;
        }
        String n = normalize(content);
        if (n.length() < OVERLAP_MIN_CHARS) {
            return false;
        }
        for (SemanticFact fact : userFacts()) {
            if (normalize(fact.getObjectValue()).contains(n)
                    || n.contains(normalize(fact.getObjectValue()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUserFact(String content) {
        String n = normalize(content);
        if (n.isEmpty()) {
            return false;
        }
        for (SemanticFact fact : userFacts()) {
            if (n.equals(normalize(fact.getObjectValue()))) {
                return true;
            }
        }
        return alreadyInStructured(content);
    }

    private List<SemanticFact> userFacts() {
        try {
            return memoryManager.queryFacts(
                    MemoryStore.effectiveTenantId(),
                    MemoryScope.ScopeType.USER.name(),
                    MemoryStore.effectiveUserIdString(),
                    null).stream()
                    .filter(SemanticFact::isValid)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private MemoryContext loadStructured(String sessionId, String query, MemoryReadPolicy policy) {
        AgentContext ctx = new AgentContext();
        ctx.setTenantId(MemoryStore.effectiveTenantId());
        ctx.setUserId(MemoryStore.effectiveUserIdString());
        ctx.setSessionId(sessionId);
        ctx.setGoal(query);
        MemoryContext raw = memoryManager.buildContext(ctx);
        if (raw == null) {
            return new MemoryContext();
        }
        if (!policy.working()) {
            raw.setWorkingMemory(null);
        }
        if (!policy.longTerm()) {
            raw.setFacts(new ArrayList<>());
            raw.setEpisodes(new ArrayList<>());
            raw.setSkills(new ArrayList<>());
            raw.setRawMemories(new ArrayList<>());
        }
        if (!policy.user()) {
            raw.setPreferences(new ArrayList<>());
        } else {
            for (SemanticFact fact : userFacts()) {
                String value = fact.getObjectValue();
                if (value != null && !raw.getPreferences().contains(value)) {
                    raw.addPreference(value);
                }
            }
        }
        return raw;
    }

    private MemoryContext userOnlyContext() {
        MemoryContext raw = new MemoryContext();
        for (SemanticFact fact : userFacts()) {
            String value = fact.getObjectValue();
            if (value != null) {
                raw.addPreference(value);
            }
        }
        return raw;
    }

    private static String formatStructured(MemoryContext ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 记忆\n");
        if (ctx.getWorkingMemory() != null && ctx.getWorkingMemory().getGoal() != null) {
            sb.append("当前任务: ").append(ctx.getWorkingMemory().getGoal()).append('\n');
            if (ctx.getWorkingMemory().getCurrentTaskId() != null) {
                sb.append("当前步骤: ")
                        .append(ctx.getWorkingMemory().getCurrentTaskId()).append('\n');
            }
        }
        appendList(sb, "已知事实", ctx.getFacts());
        if (ctx.getEpisodes() != null && !ctx.getEpisodes().isEmpty()) {
            sb.append("\n历史经验:\n");
            for (Episode ep : ctx.getEpisodes()) {
                sb.append("- ").append(ep.getTaskSummary());
                if (ep.getResolution() != null) {
                    sb.append(" → ").append(ep.getResolution());
                }
                sb.append('\n');
            }
        }
        appendList(sb, "可用方法", ctx.getSkills());
        appendList(sb, "用户偏好", ctx.getPreferences());
        return sb.toString().trim();
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(":\n");
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
    }

    /**
     * ponytail: 子串去重；语义近邻需 embedding 后再上。
     */
    static String dropOverlappingBlobLines(String blob, String structured) {
        if (StringUtils.isBlank(blob) || StringUtils.isBlank(structured)) {
            return blob;
        }
        String structuredNorm = normalize(structured);
        StringBuilder kept = new StringBuilder();
        for (String line : blob.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= OVERLAP_MIN_CHARS
                    && !trimmed.startsWith("═")
                    && structuredNorm.contains(normalize(trimmed))) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return kept.toString().trim();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
