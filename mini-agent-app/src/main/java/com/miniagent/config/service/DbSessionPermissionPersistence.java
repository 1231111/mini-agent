package com.miniagent.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionPersistence;
import com.miniagent.config.entity.AgentSessionPermission;
import com.miniagent.config.repository.AgentSessionPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把会话权限落库。{@code askGrantedTools} + {@code askGrants} + {@code alwaysAllowedTools}
 * 全部序列化进 {@link AgentSessionPermission#getAskGrantsJson()} 这一个 JSON 字段。
 *
 * <p><b>工具名集合本身不构成授权</b>：{@code askGrantedTools}（以及旧数组格式里的名字）
 * 只用于回显，一次性授权的判据始终来自带参数摘要的 {@code askGrants}。
 * {@code alwaysAllowedTools} 是用户显式点选、可撤销、作用域仅限本会话的独立维度。
 *
 * <p>JSON 形状按兼容顺序共存：
 * <ul>
 *   <li>旧格式 A：工具名数组 {@code ["a","b"]}；</li>
 *   <li>旧格式 B：扁平 {@code Map<token, AskGrant>} 对象 {@code {"tok":{...}}}；</li>
 *   <li>新格式：带 {@code "alwaysAllowedTools"} 键的包装对象。读取时按形状分支，
 *       有这个数组键才按新格式解，否则退回旧格式 B。</li>
 * </ul>
 * 没有「始终允许」数据时仍写回旧格式 A/B，保证存量数据字节级不变、零回归。
 */
@Service
public class DbSessionPermissionPersistence implements SessionPermissionPersistence {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    @Autowired
    private AgentSessionPermissionRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public State loadOrCreate(String sessionId) {
        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            AgentSessionPermission existing =
                    repository.findById(sessionId).orElse(null);
            if (existing != null) {
                return state(existing);
            }
            try {
                AgentSessionPermission created = new AgentSessionPermission();
                created.setSessionId(sessionId);
                created.setMode(PermissionMode.DEFAULT.wireName());
                created.setPlanApproved(false);
                created.setAskGrantsJson("[]");
                created.setConfirmPolicy(
                        ConfirmPolicy.DANGEROUS.wireName());
                return state(repository.saveAndFlush(created));
            } catch (DataIntegrityViolationException e) {
                AgentSessionPermission concurrent =
                        repository.findById(sessionId).orElse(null);
                if (concurrent != null) {
                    return state(concurrent);
                }
                if (attempt == MAX_CREATE_ATTEMPTS - 1) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException(
                "Unable to initialize permissions for session " + sessionId);
    }

    @Override
    public boolean compareAndSet(
            String sessionId, State expected, State next) {
        if (next.version() != expected.version() + 1L) {
            throw new IllegalArgumentException(
                    "Permission CAS must increment version by one");
        }
        return repository.compareAndSet(
                sessionId,
                expected.version(),
                next.mode(),
                next.planApproved(),
                writeGrants(next.askGrantedTools(), next.askGrants(),
                        next.alwaysAllowedTools()),
                next.confirmPolicy(),
                next.execPolicyOverride(),
                LocalDateTime.now()) == 1;
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        repository.deleteById(sessionId);
    }

    private State state(AgentSessionPermission entity) {
        GrantData grants = readGrants(entity.getAskGrantsJson());
        return new State(
                entity.getMode(),
                entity.isPlanApproved(),
                grants.toolNames(),
                grants.grants(),
                entity.getConfirmPolicy(),
                entity.getExecPolicyOverride(),
                grants.alwaysAllowedTools(),
                entity.getVersion());
    }

    /** 按 JSON 形状分支读取：数组 / 旧扁平对象 / 新包装对象。 */
    private GrantData readGrants(String json) {
        if (json == null || json.isBlank()) {
            return new GrantData(Set.of(), Map.of(), Set.of());
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                // 既有安装存的是工具名数组。名字集合本身不构成授权，
                // 这里只做兼容回显；一次性授权的判据始终来自 askGrants。
                Set<String> names = objectMapper.readValue(
                        trimmed, new TypeReference<Set<String>>() {});
                return new GrantData(
                        names == null ? Set.of() : names, Map.of(), Set.of());
            }
            if (trimmed.startsWith("{")) {
                JsonNode root = objectMapper.readTree(trimmed);
                if (root.isObject() && root.has("alwaysAllowedTools")
                        && root.get("alwaysAllowedTools").isArray()) {
                    // 新格式：带 "alwaysAllowedTools" 键的包装对象。
                    Set<String> names = readStringSet(root.get("askGrantedTools"));
                    Map<String, AskGrant> grants = readGrantMap(root.get("askGrants"));
                    // 兼容只写了 grants、没回显 askGrantedTools 的包装对象。
                    if (names.isEmpty() && !grants.isEmpty()) {
                        names = toolNamesOf(grants);
                    }
                    return new GrantData(names, grants,
                            readStringSet(root.get("alwaysAllowedTools")));
                }
                // 旧格式：扁平 Map<token, AskGrant>。
                Map<String, AskGrant> grants = objectMapper.readValue(
                        trimmed, new TypeReference<Map<String, AskGrant>>() {});
                return new GrantData(toolNamesOf(grants), grants, Set.of());
            }
            throw new IllegalStateException("Invalid permission grants JSON: " + trimmed);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid permission grants JSON", e);
        }
    }

    /** 有「始终允许」数据时写新包装对象；否则保持旧格式，字节级不变。 */
    private String writeGrants(Set<String> names, Map<String, AskGrant> grants,
                               Set<String> alwaysAllowedTools) {
        try {
            if (alwaysAllowedTools != null && !alwaysAllowedTools.isEmpty()) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("askGrantedTools",
                        names == null ? Set.of() : Set.copyOf(names));
                wrapper.put("askGrants",
                        grants == null ? Map.of() : Map.copyOf(grants));
                // 这个键既是数据也是形状标记：读取端据此与旧扁平 Map 区分。
                wrapper.put("alwaysAllowedTools", Set.copyOf(alwaysAllowedTools));
                return objectMapper.writeValueAsString(wrapper);
            }
            if (grants != null && !grants.isEmpty()) {
                return objectMapper.writeValueAsString(grants);
            }
            return objectMapper.writeValueAsString(
                    names == null ? Set.of() : Set.copyOf(names));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist permission grants", e);
        }
    }

    private Set<String> readStringSet(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (JsonNode element : node) {
            if (element.isTextual()) {
                out.add(element.asText());
            }
        }
        return Set.copyOf(out);
    }

    private Map<String, AskGrant> readGrantMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(
                node, new TypeReference<Map<String, AskGrant>>() {});
    }

    private static Set<String> toolNamesOf(Map<String, AskGrant> grants) {
        return grants.values().stream()
                .map(AskGrant::toolName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record GrantData(Set<String> toolNames, Map<String, AskGrant> grants,
                             Set<String> alwaysAllowedTools) {
    }
}
