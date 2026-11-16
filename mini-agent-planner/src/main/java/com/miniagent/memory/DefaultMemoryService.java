package com.miniagent.memory;

import com.miniagent.common.context.OwnerContext;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
            return replaceUserFact(oldText, newContent);
        }
        return memoryStore.replace(target, oldText, newContent);
    }

    @Override
    public Map<String, Object> remove(String target, String oldText) {
        if (MemoryKeys.TARGET_USER.equals(target) && memoryManager != null) {
            return removeUserFact(oldText);
        }
        return memoryStore.remove(target, oldText);
    }

    @Override
    public List<String> read(String target) {
        List<String> out = new ArrayList<>(memoryStore.readEntries(target));
        if (MemoryKeys.TARGET_USER.equals(target) && memoryManager != null) {
            for (SemanticFact fact : userFacts()) {
                String value = fact.getObjectValue();
                if (value != null) {
                    // facts 按旧到新排列；同值保留最近一次保存的位置，供界面反向显示。
                    out.remove(value);
                    out.add(value);
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
        return writeUserFact(content, null);
    }

    private Map<String, Object> writeUserFact(String content, String subject) {
        if (StringUtils.isBlank(content)) {
            return userResult(false, "内容不能为空。");
        }
        content = content.trim();
        String scanErr = SecurityScanner.scan(content);
        if (scanErr != null) {
            return userResult(false, scanErr);
        }
        SemanticFact fact = new SemanticFact();
        fact.setTenantId(OwnerContext.effectiveTenantId());
        fact.setScope(MemoryScope.ofUser(
                OwnerContext.effectiveTenantId(), OwnerContext.effectiveUserId()));
        // 替换时保留原事实身份，writeFact 才会把旧值归档，而不是另加一条冲突偏好。
        fact.setSubject(subject == null ? clip(content, FACT_SUBJECT_MAX) : subject);
        fact.setPredicate(MemoryKeys.PREDICATE_PREFERENCE);
        fact.setObjectValue(content);
        fact.setSource(MemoryKeys.SOURCE_MEMORY_TOOL);
        memoryManager.writeFact(fact);
        SemanticFact persisted = userFacts().stream()
                .filter(saved -> Objects.equals(saved.getSubject(), fact.getSubject())
                        && Objects.equals(saved.getPredicate(), fact.getPredicate())
                        && Objects.equals(saved.getObjectValue(), fact.getObjectValue()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("用户偏好写入后回读校验失败，请读取记忆确认实际状态。"));
        Map<String, Object> ok = userResult(true, "已写入用户偏好并完成回读校验。");
        ok.put("storage", "semantic_fact");
        if (persisted.getId() != null) {
            ok.put("fact_id", persisted.getId());
        }
        ok.put("readback", persisted.getObjectValue());
        return ok;
    }

    private Map<String, Object> replaceUserFact(String oldText, String newContent) {
        if (StringUtils.isBlank(oldText)) {
            return userResult(false, "old_text 不能为空。");
        }
        if (StringUtils.isBlank(newContent)) {
            return userResult(false, "内容不能为空。使用 remove 删除条目。");
        }
        List<SemanticFact> facts = userFacts();
        List<String> matches = matchingUserEntries(oldText, facts);
        if (matches.size() != 1) {
            return userMatchFailure(matches);
        }
        String previous = matches.getFirst();
        List<SemanticFact> matchedFacts = facts.stream()
                .filter(fact -> previous.equals(fact.getObjectValue())).toList();
        if (matchedFacts.isEmpty()) {
            Map<String, Object> result = memoryStore.replace(MemoryKeys.TARGET_USER, previous, newContent);
            if (Boolean.TRUE.equals(result.get("success"))) {
                requireUserEntry(newContent.trim(), true);
                result.put("persisted", true);
                result.put("storage", "user_blob");
                result.put("readback", newContent.trim());
            }
            return result;
        }
        SemanticFact original = matchedFacts.getFirst();
        Map<String, Object> result = writeUserFact(newContent, original.getSubject());
        if (!Boolean.TRUE.equals(result.get("success"))) {
            return result;
        }
        // 遗留的同值不同身份事实也必须归档，避免旧偏好继续被注入提示。
        for (SemanticFact duplicate : matchedFacts) {
            if (!Objects.equals(duplicate.getSubject(), original.getSubject())
                    || !Objects.equals(duplicate.getPredicate(), original.getPredicate())) {
                removeOwnedFact(duplicate);
            }
        }
        removeLegacyUserEntry(previous);
        if (!previous.equals(newContent.trim())) {
            requireUserEntry(previous, false);
        }
        result.put("message", "用户偏好已替换并完成回读校验。");
        return result;
    }

    private Map<String, Object> removeUserFact(String oldText) {
        if (StringUtils.isBlank(oldText)) {
            return userResult(false, "old_text 不能为空。");
        }
        List<SemanticFact> facts = userFacts();
        List<String> matches = matchingUserEntries(oldText, facts);
        if (matches.size() != 1) {
            return userMatchFailure(matches);
        }
        String previous = matches.getFirst();
        for (SemanticFact fact : facts) {
            if (previous.equals(fact.getObjectValue())) {
                removeOwnedFact(fact);
            }
        }
        removeLegacyUserEntry(previous);
        requireUserEntry(previous, false);
        Map<String, Object> result = userResult(true, "用户偏好已删除并完成回读校验。");
        result.put("removed", previous);
        return result;
    }

    private List<String> matchingUserEntries(String oldText, List<SemanticFact> facts) {
        List<String> entries = new ArrayList<>(memoryStore.readEntries(MemoryKeys.TARGET_USER));
        for (SemanticFact fact : facts) {
            if (fact.getObjectValue() != null) {
                entries.add(fact.getObjectValue());
            }
        }
        return entries.stream().filter(entry -> entry.contains(oldText.trim())).distinct().toList();
    }

    private static Map<String, Object> userMatchFailure(List<String> matches) {
        return userResult(false, matches.isEmpty() ? "没有找到匹配的用户偏好。请先读取记忆。"
                : "多个用户偏好匹配，请提供更具体的 old_text。");
    }

    private void removeOwnedFact(SemanticFact fact) {
        if (fact.getId() == null || !memoryManager.removeFact(OwnerContext.effectiveTenantId(),
                MemoryScope.ScopeType.USER.name(), OwnerContext.effectiveUserId(), fact.getId())) {
            throw new IllegalStateException("用户偏好删除失败，请读取记忆确认实际状态。");
        }
    }

    private void removeLegacyUserEntry(String content) {
        if (memoryStore.readEntries(MemoryKeys.TARGET_USER).contains(content)
                && !Boolean.TRUE.equals(memoryStore.remove(MemoryKeys.TARGET_USER, content).get("success"))) {
            throw new IllegalStateException("遗留用户偏好删除失败，请读取记忆确认实际状态。");
        }
    }

    private void requireUserEntry(String content, boolean present) {
        if (read(MemoryKeys.TARGET_USER).contains(content) != present) {
            throw new IllegalStateException("用户偏好操作后回读校验失败，请读取记忆确认实际状态。");
        }
    }

    private static Map<String, Object> userResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("persisted", success);
        result.put("target", MemoryKeys.TARGET_USER);
        result.put("message", message);
        return result;
    }

    private boolean alreadyInStructured(String content) {
        if (memoryManager == null || StringUtils.isBlank(content)) {
            return false;
        }
        String n = normalize(content);
        if (n.length() < OVERLAP_MIN_CHARS) {
            return false;
        }
        for (SemanticFact fact : activeUserFacts()) {
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
        return userFacts().stream().anyMatch(fact -> {
            String value = normalize(fact.getObjectValue());
            return value.length() >= OVERLAP_MIN_CHARS
                    && (value.contains(n) || n.contains(value));
        });
    }

    private List<SemanticFact> userFacts() {
        return activeUserFacts().stream()
                .filter(fact -> MemoryKeys.PREDICATE_PREFERENCE.equals(fact.getPredicate()))
                .toList();
    }

    private List<SemanticFact> activeUserFacts() {
        // 读取失败必须让工具报告失败；空列表会把存储故障伪装成“用户没有偏好”。
        return memoryManager.queryFacts(
                OwnerContext.effectiveTenantId(),
                MemoryScope.ScopeType.USER.name(),
                OwnerContext.effectiveUserId(),
                null).stream()
                .filter(SemanticFact::isValid)
                .sorted(Comparator.comparingLong(SemanticFact::getCreatedAt)
                        .thenComparing(SemanticFact::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private MemoryContext loadStructured(String sessionId, String query, MemoryReadPolicy policy) {
        AgentContext ctx = new AgentContext();
        ctx.setTenantId(OwnerContext.effectiveTenantId());
        ctx.setUserId(OwnerContext.effectiveUserId());
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
