package com.miniagent.agent.todo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.task.TaskStep;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.planner.DoneWhen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Agent 循环复用的 Todo 列表。
 * 工业门禁：depends_on 依赖拓扑、双轨验收、可插拔 Validator、reopen 回滚、blocked 熔断。
 */
@Component
public class TaskTodoStore {

    private static final Logger log = LoggerFactory.getLogger(TaskTodoStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Status { pending, in_progress, awaiting_confirm, completed, cancelled, blocked }

    /** 一个任务的清单分区：内容 + 是否已作废（作废后仍保留，可 restore）。 */
    private record Partition(List<TodoItem> items, boolean archived) {}

    /** 危险操作确认：上线/部署，不含「生成并发布 Excel」这类写盘标题 */
    private static final java.util.regex.Pattern DANGEROUS_GOAL =
            java.util.regex.Pattern.compile(
                    "(?i)(上线|发布到|发布上线|部署到|生产环境|正式提交|删除全部"
                            + "|drop table|rm -rf|格式化|清空数据库|exec_command)");
    private static final int YIELD_NOTE_MAX_CHARS = 200;

    /**
     * 一个会话最多保留多少个任务分区。
     *
     * <p>分区直接拼进同一行的 {@code active_json}，不设上限的话这个字段会随
     * 任务数无限增长（每个任务一份清单）。超限时丢最早的任务，且绝不丢当前任务。</p>
     */
    private static final int MAX_TASK_PARTITIONS = 24;

    /**
     * @param dependsOn 依赖的上游 todo id；未满足前不可推进/勾选
     */
    public record TodoItem(int id, String content, Status status, String note,
                           String doneWhen, String evidence, String validationHash,
                           List<Integer> dependsOn) {
        public TodoItem {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            validationHash = validationHash == null ? "" : validationHash;
            evidence = evidence == null ? "" : evidence;
            doneWhen = doneWhen == null ? "" : doneWhen;
            note = note == null ? "" : note;
        }

        public TodoItem(int id, String content, Status status, String note) {
            this(id, content, status, note, "", "", "", List.of());
        }

        public TodoItem(int id, String content, Status status, String note,
                        String doneWhen, String evidence) {
            this(id, content, status, note, doneWhen, evidence, "", List.of());
        }

        public TodoItem(int id, String content, Status status, String note,
                        String doneWhen, String evidence, String validationHash) {
            this(id, content, status, note, doneWhen, evidence, validationHash, List.of());
        }
    }

    private final Map<String, List<TodoItem>> todos = new ConcurrentHashMap<>();
    /**
     * 已作废但保留的清单（{@code task.clear} 的产物）。
     *
     * <p>作废与删除的区别：作废只是「当前任务不再使用这份清单」，
     * 内容仍在，{@code task.restore} 或 {@code task.resume} 回到该任务时能取回。
     * 删除则不可逆 —— 用户按「清空」时想的是前者。</p>
     */
    private final Map<String, List<TodoItem>> archived = new ConcurrentHashMap<>();
    /** 文件 mtime：仅 file 回退模式 */
    private final Map<String, Long> fileMtimes = new ConcurrentHashMap<>();
    private final Path persistDir;
    private final List<TodoStepValidator> validators;
    private SessionTodoPersistence persistence;
    private SessionEventCenter eventCenter;
    /**
     * 任务作用域来源。清单按 {@code TaskScope.scopeKey(sessionId)} 分键，
     * 与 {@code PlannerStateStore}、{@code ContextCompressor} 同一口径 ——
     * 任务级状态用同一个 key，才不会出现「图换了任务、清单还留在原处」。
     */
    private com.miniagent.agent.scope.TaskScopeRegistry scopeRegistry;

    /** 测试 / 无 Spring 场景 */
    public TaskTodoStore(String persistDir) {
        this(persistDir, List.of(new BuiltinSemanticTodoValidator()));
    }

    @Autowired
    public TaskTodoStore(@Value("${agent.todo.persist-dir:${agent.data-dir:${user.home}/.miniagent}/workspace/.todos}") String persistDir,
                         @Autowired(required = false) List<TodoStepValidator> validators) {
        this.persistDir = Path.of(persistDir).toAbsolutePath().normalize();
        this.validators = validators == null || validators.isEmpty()
                ? List.of(new BuiltinSemanticTodoValidator())
                : List.copyOf(validators);
        try {
            Files.createDirectories(this.persistDir);
        } catch (Exception e) {
            log.warn("无法创建 todo 持久化目录 {}: {}", this.persistDir, e.getMessage());
        }
    }

    @Autowired(required = false)
    public void setPersistence(SessionTodoPersistence persistence) {
        this.persistence = persistence;
        if (persistence != null) {
            log.info("TaskTodoStore 使用 DB 持久化（多副本）");
        }
    }

    @Autowired(required = false)
    public void setEventCenter(SessionEventCenter eventCenter) {
        this.eventCenter = eventCenter;
    }

    @Autowired(required = false)
    public void setScopeRegistry(com.miniagent.agent.scope.TaskScopeRegistry scopeRegistry) {
        this.scopeRegistry = scopeRegistry;
    }

    private boolean useDb() {
        return persistence != null;
    }

    public synchronized List<TodoItem> get(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        return Collections.unmodifiableList(todos.getOrDefault(key, Collections.emptyList()));
    }

    public synchronized boolean hasPlan(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.get(key);
        return list != null && !list.isEmpty();
    }

    public synchronized boolean hasIncomplete(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.pending || it.status() == Status.in_progress
                    || it.status() == Status.awaiting_confirm) {
                return true;
            }
        }
        return false;
    }

    /** pending / in_progress 仍可自动推进；awaiting_confirm 应把问句交给用户。 */
    public synchronized boolean hasRunnableIncomplete(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.pending || it.status() == Status.in_progress) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasAwaitingConfirm(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.awaiting_confirm) {
                return true;
            }
        }
        return false;
    }

    public synchronized TodoItem awaitingConfirmItem(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.awaiting_confirm) {
                return it;
            }
        }
        return null;
    }

    public synchronized boolean hasBlocked(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.blocked) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean canSucceed(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) {
            return true;
        }
        for (TodoItem it : list) {
            if (it.status() == Status.blocked) {
                return false;
            }
            if (it.status() != Status.completed && it.status() != Status.cancelled) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean allTerminal(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) {
            return true;
        }
        for (TodoItem it : list) {
            if (it.status() != Status.completed && it.status() != Status.cancelled
                    && it.status() != Status.blocked) return false;
        }
        return true;
    }

    public synchronized List<TodoItem> set(String sessionId, List<Map<String, Object>> rawItems) {
        return write(sessionId, rawItems, true);
    }

    /**
     * 规划器投影：状态以传入为准，不按旧条目 rank 合并，也不 promote。
     */
    public synchronized List<TodoItem> overwrite(
            String sessionId, List<Map<String, Object>> rawItems) {
        return write(sessionId, rawItems, false);
    }

    private List<TodoItem> write(
            String sessionId, List<Map<String, Object>> rawItems, boolean mergeRanks) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> prev = todos.getOrDefault(key, Collections.emptyList());
        // A model cannot replace the frozen DAG contract through task.set (including by renaming IDs).
        if (mergeRanks && containsDagProjection(key)) {
            return new ArrayList<>(prev);
        }
        List<TodoItem> items = new ArrayList<>();
        if (rawItems != null) {
            int autoId = 1;
            Integer prevId = null;
            for (Map<String, Object> raw : rawItems) {
                if (raw == null) {
                    continue;
                }
                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                if (content.isEmpty()) {
                    continue;
                }
                Status status = parseStatus(String.valueOf(raw.getOrDefault("status", "pending")));
                String note = str(raw.get("note"));
                String doneWhen = serializeDoneWhen(raw.getOrDefault("done_when", raw.get("doneWhen")), !mergeRanks);
                String evidence = str(raw.get("evidence"));
                String hash = str(raw.getOrDefault("validation_hash", raw.get("validationHash")));
                int id = raw.get("id") instanceof Number n ? n.intValue() : autoId;
                if (id <= 0) {
                    id = autoId;
                }

                boolean depsSpecified = raw.containsKey("depends_on")
                        || raw.containsKey("dependsOn");
                List<Integer> deps = parseDependsOn(
                        raw.getOrDefault("depends_on", raw.get("dependsOn")));
                if (!depsSpecified && prevId != null) {
                    deps = List.of(prevId);
                }

                if (mergeRanks) {
                    for (TodoItem old : prev) {
                        if (old.id() == id && old.content().equals(content)
                                && rank(old.status()) > rank(status)) {
                            status = old.status();
                            if (note.isEmpty()) {
                                note = old.note();
                            }
                            if (doneWhen.isEmpty()) {
                                doneWhen = old.doneWhen();
                            }
                            if (evidence.isEmpty()) {
                                evidence = old.evidence();
                            }
                            if (hash.isEmpty()) {
                                hash = old.validationHash();
                            }
                            if (!depsSpecified && !old.dependsOn().isEmpty()) {
                                deps = old.dependsOn();
                            }
                            break;
                        }
                    }
                }

                if (mergeRanks && status == Status.completed && structuredSemanticContract(doneWhen)) {
                    status = Status.in_progress;
                    note = "语义条件尚未验证，须由 DAG 验收后完成";
                }
                items.add(new TodoItem(
                        id, content, status, note, doneWhen, evidence, hash, deps));
                prevId = id;
                autoId = Math.max(autoId, id) + 1;
            }
        }
        if (mergeRanks) {
            promoteReadyItems(items);
        }
        todos.put(key, items);
        persist(key, items);
        return new ArrayList<>(items);
    }

    private static int rank(Status s) {
        return switch (s) {
            case completed -> 5;
            case blocked -> 4;
            case in_progress -> 3;
            case awaiting_confirm -> 2;
            case cancelled -> 1;
            case pending -> 0;
        };
    }

    /** Keep structured contracts losslessly in the legacy persisted string field. */
    private static String serializeDoneWhen(Object raw, boolean dagProjection) {
        if (raw == null) return "";
        if (raw instanceof CharSequence text) return text.toString().trim();
        try {
            JsonNode node = MAPPER.valueToTree(raw);
            DoneWhen contract = DoneWhen.parse(node);
            if (!contract.valid()) return "{\"invalid\":true}";
            var structured = MAPPER.valueToTree(contract);
            if (dagProjection) ((com.fasterxml.jackson.databind.node.ObjectNode) structured).put("dagManaged", true);
            return MAPPER.writeValueAsString(structured);
        } catch (Exception invalid) {
            return "{\"invalid\":true}";
        }
    }

    private static boolean dagManaged(String contract) {
        if (contract == null || !contract.startsWith("{")) return false;
        try { return MAPPER.readTree(contract).path("dagManaged").asBoolean(false); }
        catch (Exception invalid) { return false; }
    }

    private boolean containsDagProjection(String key) {
        return todos.getOrDefault(key, List.of()).stream().anyMatch(it -> dagManaged(it.doneWhen()))
                || archived.getOrDefault(key, List.of()).stream().anyMatch(it -> dagManaged(it.doneWhen()));
    }

    private static boolean structuredSemanticContract(String contract) {
        if (contract == null || !contract.startsWith("{")) return false;
        try {
            JsonNode node = MAPPER.readTree(contract);
            DoneWhen parsed = DoneWhen.parse(node);
            return node.path("invalid").asBoolean(false) || !parsed.valid() || !parsed.semanticChecks().isEmpty();
        } catch (Exception invalid) { return true; }
    }

    static String legacyDoneWhen(String contract) {
        if (contract == null || !contract.startsWith("{")) return contract;
        try { return DoneWhen.parse(MAPPER.readTree(contract)).wire(); }
        catch (Exception invalid) { return contract; }
    }

    static boolean needsConfirmGate(TodoItem it) {
        if (it == null) {
            return false;
        }
        if (PermissionContext.confirmPolicy() == ConfirmPolicy.AUTO
                || PermissionContext.mode() == PermissionMode.ACCEPT_EDITS) {
            return false;
        }
        String c = it.content() == null ? "" : it.content();
        return DANGEROUS_GOAL.matcher(c).find();
    }

    public synchronized List<TodoItem> update(String sessionId, int id, String statusStr,
                                              String note, String evidence, String[] errorOut) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) {
                continue;
            }
            Status status = statusStr == null || statusStr.isBlank() ? it.status() : parseStatus(statusStr);
            String newNote = note == null || note.isBlank() ? it.note() : note;
            String newEvidence = evidence == null || evidence.isBlank() ? it.evidence() : evidence.trim();
            if (newEvidence.isEmpty() && newNote != null && !newNote.isBlank()) {
                newEvidence = newNote;
            }
            String newHash = it.validationHash();

            if (status == Status.in_progress || status == Status.completed
                    || status == Status.awaiting_confirm) {
                String depErr = dependencyGate(list, it);
                if (depErr != null) {
                    if (errorOut != null && errorOut.length > 0) {
                        errorOut[0] = depErr;
                    }
                    return null;
                }
            }
            // 禁止跳过 CONFIRM：关键步不能直接从 pending 改成 in_progress
            if (status == Status.in_progress && it.status() == Status.pending && needsConfirmGate(it)) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "关键步骤须由用户在页面确认，不能直接 in_progress";
                }
                return null;
            }
            if (status == Status.in_progress && it.status() == Status.awaiting_confirm) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "请等待用户在页面确认，不要用 update 绕过确认门禁";
                }
                return null;
            }

            if (status == Status.completed) {
                String check = verifyCompletion(it.doneWhen(), newEvidence);
                if (check != null) {
                    if (errorOut != null && errorOut.length > 0) {
                        errorOut[0] = check;
                    }
                    return null;
                }
                String pluginErr = runValidators(it, newEvidence);
                if (pluginErr != null) {
                    if (errorOut != null && errorOut.length > 0) {
                        errorOut[0] = pluginErr;
                    }
                    return null;
                }
                newHash = BuiltinSemanticTodoValidator.hashOf(
                        new TodoItem(it.id(), it.content(), it.status(), newNote,
                                it.doneWhen(), newEvidence, "", it.dependsOn()),
                        newEvidence);
            }

            list.set(i, new TodoItem(it.id(), it.content(), status, newNote,
                    it.doneWhen(), newEvidence, newHash, it.dependsOn()));

            if (status == Status.completed) {
                promoteReadyItems(list);
            }
            persist(key, list);
            return new ArrayList<>(list);
        }
        if (errorOut != null && errorOut.length > 0) {
            errorOut[0] = "未找到 id=" + id + " 的子任务";
        }
        return null;
    }

    public synchronized List<TodoItem> update(String sessionId, int id, String statusStr, String note) {
        String[] err = new String[1];
        List<TodoItem> r = update(sessionId, id, statusStr, note, null, err);
        return r != null ? r : get(sessionId);
    }

    /**
     * 回滚已完成/阻塞步骤为 in_progress，并级联将其下游（直接/间接依赖方）重置为 pending。
     */
    public synchronized List<TodoItem> reopen(String sessionId, int id, String reason, String[] errorOut) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        TodoItem target = findById(list, id);
        if (target == null) {
            if (errorOut != null && errorOut.length > 0) {
                errorOut[0] = "未找到 id=" + id;
            }
            return null;
        }
        if (target.status() != Status.completed && target.status() != Status.blocked) {
            if (errorOut != null && errorOut.length > 0) {
                errorOut[0] = "仅 completed/blocked 可 reopen，当前状态=" + target.status();
            }
            return null;
        }

        Set<Integer> cascade = collectDependents(list, id);
        String note = reason == null || reason.isBlank() ? "reopened" : "reopened: " + reason.trim();

        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() == id) {
                list.set(i, new TodoItem(it.id(), it.content(), Status.in_progress, note,
                        it.doneWhen(), "", "", it.dependsOn()));
            } else if (cascade.contains(it.id())
                    && (it.status() == Status.completed || it.status() == Status.in_progress
                    || it.status() == Status.blocked)) {
                list.set(i, new TodoItem(it.id(), it.content(), Status.pending,
                        "upstream reopened #" + id, it.doneWhen(), "", "", it.dependsOn()));
            }
        }
        promoteReadyItems(list);
        persist(key, list);
        log.info("todo #{} reopened，级联重置 {} 个下游", id, cascade.size());
        return new ArrayList<>(list);
    }

    /**
     * 全自动（自动编辑 + 自动确认）生效时，把因为危险步闸门停在 awaiting_confirm 的条目放开，
     * 返回放开条数。
     *
     * <p>为什么只放这一部分：{@code awaiting_confirm} 有两个来源 —— 危险步闸门，以及
     * 「缺密钥 / 须用户补充信息」。后者即便全自动也必须等用户，放开了只会让模型空转。
     * 判定沿用 {@link #needsConfirmGate} 用过的同一个 {@code DANGEROUS_GOAL}：
     * 从哪一类进去，就从哪一类出来。</p>
     *
     * <p>依赖未满足的条目不动 —— 放行是撤掉人工确认，不是跳过依赖顺序。</p>
     */
    public synchronized int releaseConfirmGatedItems(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.get(key);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int released = 0;
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.status() != Status.awaiting_confirm) {
                continue;
            }
            String c = it.content() == null ? "" : it.content();
            if (!DANGEROUS_GOAL.matcher(c).find() || dependencyUnmet(list, it)) {
                continue;
            }
            String note = it.note() == null || it.note().isBlank()
                    ? "FULL_AUTO" : "FULL_AUTO: " + it.note().trim();
            list.set(i, new TodoItem(it.id(), it.content(), Status.in_progress, note,
                    it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn()));
            released++;
        }
        if (released > 0) {
            promoteReadyItems(list);
            persist(key, list);
            log.info("全自动生效，放开危险步确认 session={} 条数={}", sessionId, released);
        }
        return released;
    }

    /**
     * 关键路径确认：将 awaiting_confirm → in_progress，允许开始执行。
     */
    public synchronized List<TodoItem> confirm(String sessionId, int id, String note, String[] errorOut) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) {
                continue;
            }
            if (it.status() != Status.awaiting_confirm) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "仅 awaiting_confirm 可 confirm，当前=" + it.status();
                }
                return null;
            }
            String depErr = dependencyGate(list, it);
            if (depErr != null) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = depErr;
                }
                return null;
            }
            String n = note == null || note.isBlank() ? "CONFIRM" : note.trim();
            if (!n.toUpperCase().contains("CONFIRM")) {
                n = "CONFIRM: " + n;
            }
            // 确认前清除其他误标的 in_progress
            for (int j = 0; j < list.size(); j++) {
                TodoItem o = list.get(j);
                if (o.status() == Status.in_progress && o.id() != id) {
                    list.set(j, copyStatus(o, Status.pending));
                }
            }
            list.set(i, new TodoItem(it.id(), it.content(), Status.in_progress, n,
                    it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn()));
            persist(key, list);
            log.info("todo #{} 已 CONFIRM，开始执行", id);
            return new ArrayList<>(list);
        }
        if (errorOut != null && errorOut.length > 0) {
            errorOut[0] = "未找到 id=" + id;
        }
        return null;
    }

    /**
     * 向用户提问时挂起当前步。ids 空则挂起所有 in_progress。
     */
    public synchronized boolean yieldToHuman(String sessionId, Set<Integer> ids, String note) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.get(key);
        if (list == null || list.isEmpty()) {
            return false;
        }
        String n = truncate(note, YIELD_NOTE_MAX_CHARS);
        Set<Integer> target = new LinkedHashSet<>();
        if (ids != null) {
            for (int id : ids) {
                if (id > 0) {
                    target.add(id);
                }
            }
        }
        if (target.isEmpty()) {
            for (TodoItem it : list) {
                if (it.status() == Status.in_progress) {
                    target.add(it.id());
                }
            }
        }
        if (target.isEmpty()) {
            return hasAwaitingConfirm(sessionId);
        }
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (!target.contains(it.id())) {
                continue;
            }
            if (it.status() == Status.completed
                    || it.status() == Status.cancelled
                    || it.status() == Status.blocked) {
                continue;
            }
            if (it.status() != Status.awaiting_confirm) {
                list.set(i, new TodoItem(it.id(), it.content(), Status.awaiting_confirm, n,
                        it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn()));
                changed = true;
            }
        }
        if (changed) {
            persist(key, list);
            log.info("todo 挂起等待人工输入 session={} ids={}", sessionId, target);
        }
        return changed || hasAwaitingConfirm(sessionId);
    }

    public synchronized boolean markBlocked(String sessionId, int id, String reason) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) {
                continue;
            }
            if (it.status() == Status.completed || it.status() == Status.cancelled) {
                return false;
            }
            String note = reason == null || reason.isBlank() ? "blocked" : reason.trim();
            list.set(i, new TodoItem(it.id(), it.content(), Status.blocked, note,
                    it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn()));
            persist(key, list);
            log.warn("todo #{} 已标记 blocked: {}", id, note);
            return true;
        }
        return false;
    }

    public synchronized TodoItem activeItem(String sessionId) {
        SubGoal sg = currentSubGoalDetail(sessionId);
        if (sg == null) {
            return null;
        }
        return findById(get(sessionId), sg.id());
    }

    private String runValidators(TodoItem it, String evidence) {
        for (TodoStepValidator v : validators) {
            try {
                String err = v.validate(it, evidence);
                if (err != null && !err.isBlank()) {
                    return "[" + v.name() + "] " + err;
                }
            } catch (Exception e) {
                com.miniagent.agent.core.ModelCallControl.rethrowIfStopped(e);
                return "[" + v.name() + "] 校验异常: " + e.getMessage();
            }
        }
        return null;
    }

    /** 依赖未完成或上游产物指纹失效 → 错误信息 */
    String dependencyGate(List<TodoItem> list, TodoItem it) {
        if (it.dependsOn() == null || it.dependsOn().isEmpty()) {
            return null;
        }
        for (int depId : it.dependsOn()) {
            TodoItem dep = findById(list, depId);
            if (dep == null) {
                return "依赖 #" + depId + " 不存在";
            }
            if (dep.status() != Status.completed) {
                return "依赖未满足：#" + depId + " 状态=" + dep.status() + "（" + dep.content() + "）";
            }
            if (dep.validationHash() != null && !dep.validationHash().isBlank()) {
                TodoSemanticValidator.Result again = TodoSemanticValidator.validate(
                        dep.content(), legacyDoneWhen(dep.doneWhen()), dep.evidence());
                if (!again.ok()) {
                    return "上游产物失效：#" + depId + " " + again.error()
                            + "。请先 todo(action=reopen, id=" + depId + ")";
                }
                if (!validationHashMatches(dep.validationHash(), again.contentHash())) {
                    return "上游产物指纹不匹配：#" + depId + " 期望 hash="
                            + dep.validationHash() + " 实际=" + again.contentHash()
                            + "。请先 reopen #" + depId;
                }
            }
        }
        return null;
    }

    /** New validations store all SHA256 bits; existing 16-character persisted prefixes remain readable. */
    private static boolean validationHashMatches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return expected.equals(actual) || (expected.length() == 16 && actual.length() == 64 && actual.startsWith(expected));
    }

    private static void promoteReadyItems(List<TodoItem> list) {
        // 清掉「依赖未满足却 in_progress / awaiting_confirm」的误标
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if ((it.status() == Status.in_progress || it.status() == Status.awaiting_confirm)
                    && dependencyUnmet(list, it)) {
                list.set(i, copyStatus(it, Status.pending));
            }
        }
        boolean hasActive = list.stream().anyMatch(t ->
                t.status() == Status.in_progress || t.status() == Status.awaiting_confirm);
        if (hasActive) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.status() == Status.pending && !dependencyUnmet(list, it)) {
                if (needsConfirmGate(it)) {
                    list.set(i, copyStatus(it, Status.awaiting_confirm));
                    log.info("关键步骤 #{} 进入 awaiting_confirm，等待 CONFIRM", it.id());
                } else {
                    list.set(i, copyStatus(it, Status.in_progress));
                }
                return;
            }
        }
    }

    private static boolean dependencyUnmet(List<TodoItem> list, TodoItem it) {
        if (it.dependsOn() == null) {
            return false;
        }
        for (int depId : it.dependsOn()) {
            TodoItem dep = findById(list, depId);
            if (dep == null || dep.status() != Status.completed) {
                return true;
            }
        }
        return false;
    }

    private static TodoItem copyStatus(TodoItem it, Status status) {
        return new TodoItem(it.id(), it.content(), status, it.note(),
                it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn());
    }

    private static TodoItem findById(List<TodoItem> list, int id) {
        for (TodoItem it : list) {
            if (it.id() == id) {
                return it;
            }
        }
        return null;
    }

    private static Set<Integer> collectDependents(List<TodoItem> list, int rootId) {
        Set<Integer> out = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TodoItem it : list) {
                if (out.contains(it.id()) || it.id() == rootId) {
                    continue;
                }
                for (int d : it.dependsOn()) {
                    if (d == rootId || out.contains(d)) {
                        out.add(it.id());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> parseDependsOn(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            if (raw instanceof List<?> list) {
                List<Integer> ids = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Number n) {
                        ids.add(n.intValue());
                    }
                    else {
                        String s = String.valueOf(o).trim();
                        if (!s.isEmpty()) {
                            ids.add(Integer.parseInt(s));
                        }
                    }
                }
                return ids;
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty() || "none".equalsIgnoreCase(s) || "[]".equals(s)) {
                return List.of();
            }
            if (s.startsWith("[")) {
                List<Object> list = MAPPER.readValue(s, List.class);
                return parseDependsOn(list);
            }
            List<Integer> ids = new ArrayList<>();
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    ids.add(Integer.parseInt(part.trim()));
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final String FILE_EXISTS_PREFIX = "file_exists:";

    public String verifyCompletion(String doneWhen, String evidence) {
        if (dagManaged(doneWhen) || structuredSemanticContract(doneWhen)) {
            return "语义条件尚未验证，须由 DAG 验收后完成；请保留 in_progress 并提交实际 evidence";
        }
        String legacy = legacyDoneWhen(doneWhen);
        String dw = legacy == null ? "" : legacy.trim();
        String ev = evidence == null ? "" : evidence.trim();

        if (dw.isBlank()) {
            if (ev.isBlank()) {
                return "标记 completed 时必须提供 evidence 或 note（说明完成证据，如文件路径）";
            }
            return null;
        }

        if (dw.regionMatches(true, 0, FILE_EXISTS_PREFIX, 0, FILE_EXISTS_PREFIX.length())) {
            String path = dw.substring(FILE_EXISTS_PREFIX.length()).trim();
            if (path.isEmpty()) {
                return "done_when 的 file_exists 路径为空";
            }
            Path p = AcceptanceEvidenceFiles.resolveDeclaredFile(path);
            if (p == null) {
                return "验收失败：文件不存在 " + path
                        + "（请生成声明路径对应的产物，evidence 不能替换冻结的交付路径）";
            }
            return null;
        }
        if ("media_delivered".equalsIgnoreCase(dw) || "media".equalsIgnoreCase(dw)) {
            if (TodoSemanticValidator.acceptsMediaEvidence(ev)) {
                return null;
            }
            return "验收失败：done_when=media_delivered 需要 evidence 含图片 markdown/URL";
        }
        if ("note_required".equalsIgnoreCase(dw) || "evidence_required".equalsIgnoreCase(dw)) {
            return ev.isBlank() ? "验收失败：需要提供非空 evidence/note" : null;
        }
        if (dw.regionMatches(true, 0, "llm_judge:", 0, "llm_judge:".length())) {
            return ev.isBlank() ? "验收失败：llm_judge 需要 evidence（待评判文本或文件路径）" : null;
        }
        return ev.isBlank() ? "标记 completed 时必须提供 evidence（对照 done_when=" + dw + "）" : null;
    }

    /**
     * 从 done_when / evidence 抽出路径：剥 file_exists:，截掉括号/说明尾巴。
     * 例：file_exists:C:/a.md（28814字节…）→ C:/a.md
     */
    static String extractPathSpec(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        if (s.regionMatches(true, 0, FILE_EXISTS_PREFIX, 0, FILE_EXISTS_PREFIX.length()))
            s = s.substring(FILE_EXISTS_PREFIX.length()).trim();
        int cut = s.length();
        for (String sep : new String[]{"（", "(", "｜", "|", "；", ";"}) {
            int i = s.indexOf(sep);
            if (i > 0 && i < cut) {
                cut = i;
            }
        }
        // 空格后多为说明（保留 Windows 盘符 C:）
        int sp = s.indexOf(' ');
        if (sp > 0 && sp < cut) {
            cut = sp;
        }
        return s.substring(0, cut).trim();
    }

    private static Path resolvePath(String path) {
        String normalized = BuiltinTools.stripWorkspaceAlias(extractPathSpec(path));
        if (normalized.isEmpty()) {
            return findExistingPathInText(path);
        }
        try {
            Path p = Path.of(normalized);
            if (p.isAbsolute()) {
                return p.normalize();
            }
            Path root = BuiltinTools.effectiveWorkspaceRoot();
            Path atRoot = root.resolve(normalized).normalize();
            if (Files.exists(atRoot)) {
                return atRoot;
            }
            String task = BuiltinTools.currentTaskName();
            if (task != null && !task.isBlank()) {
                Path underTask = root.resolve(task).resolve(normalized).normalize();
                if (Files.exists(underTask)) {
                    return underTask;
                }
            }
            Path fromText = findExistingPathInText(path);
            if (fromText != null) {
                return fromText;
            }
            return atRoot;
        } catch (Exception e) {
            return findExistingPathInText(path);
        }
    }

    private static final java.util.regex.Pattern ABS_FILE =
            java.util.regex.Pattern.compile("[A-Za-z]:/[^\\s）)\"']+");

    /** evidence 里夹着绝对路径时也能对上已写入的文件。 */
    private static Path findExistingPathInText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = ABS_FILE.matcher(text.replace('\\', '/'));
        while (m.find()) {
            try {
                Path p = Path.of(m.group()).normalize();
                if (Files.exists(p)) {
                    return p;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 作废当前任务的清单（{@code task.clear}）。
     *
     * <p><b>不是删除。</b>清空的清单移进 {@link #archived} 继续保留，
     * 对外表现是「当前任务没有清单」（注入、UI、统计都看不到），
     * 但 {@code task.restore} 或 {@code task.resume} 回到这个任务时能原样取回。
     * 之前那版直接抹掉内容，用户点了「清空」就再也拿不回来。</p>
     */
    public synchronized void clear(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> items = todos.remove(key);
        if (items != null && !items.isEmpty()) {
            archived.put(key, new ArrayList<>(items));
        }
        persistRow(sessionOf(key));
        log.info("todo 已作废（保留可恢复）session={} key={} items={}",
                sessionOf(key), key, items == null ? 0 : items.size());
    }

    /** Model-driven todo clearing cannot erase a graph's frozen acceptance contract. */
    public synchronized boolean clearFromModel(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        if (containsDagProjection(key)) return false;
        clear(sessionId);
        return true;
    }

    /**
     * 取回被 {@code task.clear} 作废的清单。
     *
     * @return 恢复后的清单；没有可恢复内容时返回空列表
     */
    public synchronized List<TodoItem> restoreCleared(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> items = archived.remove(key);
        if (items == null) {
            return List.of();
        }
        todos.put(key, new ArrayList<>(items));
        persistRow(sessionOf(key));
        log.info("todo 已恢复作废清单 session={} key={} items={}",
                sessionOf(key), key, items.size());
        return List.copyOf(items);
    }

    /**
     * 当前清单是否「存在且全部终态」。
     *
     * <p>这是任务结束的唯一客观信号：库里查一下就能确认，不涉及对用户消息的任何解读。
     * 空清单<b>不算</b>终态 —— 空只说明这个任务还没列过计划，
     * 若把它当终态，每轮都会白白推进一个新任务号。</p>
     */
    public synchronized boolean hasOnlyTerminal(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.get(key);
        if (list == null || list.isEmpty()) {
            return false;
        }
        return allTerminal(list);
    }

    /**
     * 是否全部终态（completed / cancelled）。
     *
     * <p>判定「任务收尾」用的客观条件。收尾时<b>不清空</b>清单 ——
     * 曾有一版在这里清空，结果与 {@code todo action=resume} 直接冲突：
     * 恢复回去只拿到空清单。旧清单留在自己的作用域键上，恢复时原样可见。</p>
     */
    private static boolean allTerminal(List<TodoItem> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (TodoItem it : list) {
            if (it.status() != Status.completed && it.status() != Status.cancelled) {
                return false;
            }
        }
        return true;
    }

    /**
     * 回到最近离开的任务（模型显式调用 {@code todo action=resume}）。
     *
     * <p>这是「接着做」唯一的入口，由模型发起，不是框架猜。指针换回后，
     * 读清单自然走新的作用域键 —— 旧任务的条目原样还在自己那一份里，
     * 不需要从任何「挂起槽位」里恢复。</p>
     *
     * @return 恢复后的清单；没有可恢复任务时返回 {@code null}
     */
    public synchronized List<TodoItem> resumePreviousTask(String sessionId) {
        if (scopeRegistry == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (scopeRegistry.paused(sessionId) == null) {
            return null;
        }
        scopeRegistry.resolve(sessionId,
                com.miniagent.agent.scope.TaskBoundary.RESUME, "tool-resume");
        String key = safeKey(sessionId);
        ensureLoaded(key);
        // 显式回到一个任务 = 撤销它之前的「清空」。否则用户 resume 回来
        // 看到的还是空清单，恢复又成了空操作。
        if (archived.containsKey(key)) {
            restoreCleared(sessionId);
        }
        return List.copyOf(todos.getOrDefault(key, List.of()));
    }

    /**
     * 陈述「存在未完成的历史任务」，供提示词使用。
     *
     * <p>只给事实与入口，不给指令：模型看到这条自己决定要不要 resume。
     * 当前任务已有清单时不输出 —— 两个任务的清单同时出现在提示里，
     * 模型就又会把两件事混着做。</p>
     */
    public synchronized String renderResumableHint(String sessionId) {
        if (scopeRegistry == null || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        com.miniagent.agent.scope.TaskScope paused = scopeRegistry.paused(sessionId);
        if (paused == null || !get(sessionId).isEmpty()) {
            return "";
        }
        String pausedKey = paused.scopeKey();
        ensureLoaded(pausedKey);
        List<TodoItem> items = todos.getOrDefault(pausedKey, List.of());
        if (items.isEmpty() || allTerminal(items)) {
            return "";
        }
        long open = items.stream()
                .filter(it -> it.status() != Status.completed
                        && it.status() != Status.cancelled)
                .count();
        return "上一个任务（taskId=" + paused.taskId() + "）有 " + open
                + " 条未完成。用户要求接着做时，调用 todo action=resume 恢复该任务；"
                + "否则按当前目标继续。\n";
    }

    public synchronized boolean seedFromSteps(String sessionId, List<TaskStep> steps) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> existing = todos.get(key);
        if (existing != null && !existing.isEmpty()) {
            return false;
        }
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        List<TodoItem> items = new ArrayList<>();
        Integer prevId = null;
        for (TaskStep step : steps) {
            if (step == null || step.goal() == null || step.goal().isBlank()) {
                continue;
            }
            int id = step.id() > 0 ? step.id() : items.size() + 1;
            List<Integer> deps = prevId == null ? List.of() : List.of(prevId);
            items.add(new TodoItem(id, step.goal().trim(), Status.pending, "",
                    "note_required", "", "", deps));
            prevId = id;
        }
        if (items.isEmpty()) {
            return false;
        }
        promoteReadyItems(items);
        todos.put(key, items);
        persist(key, items);
        return true;
    }

    public synchronized String currentSubGoal(String sessionId) {
        SubGoal sg = currentSubGoalDetail(sessionId);
        if (sg == null) {
            return "";
        }
        return "当前子目标 (#" + sg.position() + "/" + sg.total() + ")：" + sg.text();
    }

    public synchronized SubGoal currentSubGoalDetail(String sessionId) {
        List<TodoItem> list = new ArrayList<>(get(sessionId));
        if (list.isEmpty()) {
            return null;
        }
        // 确保指针落在依赖已满足的项上
        promoteReadyItems(list);
        todos.put(safeKey(sessionId), list);
        persist(safeKey(sessionId), list);

        int total = list.size();
        int done = (int) list.stream().filter(t -> t.status() == Status.completed).count();
        TodoItem active = null;
        int position = 0;
        for (int i = 0; i < list.size(); i++) {
            TodoItem item = list.get(i);
            if (item.status() == Status.in_progress && !dependencyUnmet(list, item)) {
                active = item;
                position = i + 1;
                break;
            }
        }
        if (active == null) {
            for (int i = 0; i < list.size(); i++) {
                TodoItem item = list.get(i);
                if (item.status() == Status.awaiting_confirm && !dependencyUnmet(list, item)) {
                    active = item;
                    position = i + 1;
                    break;
                }
            }
        }
        if (active == null) {
            for (int i = 0; i < list.size(); i++) {
                TodoItem item = list.get(i);
                if (item.status() == Status.pending && !dependencyUnmet(list, item)) {
                    active = item;
                    position = i + 1;
                    break;
                }
            }
        }
        if (active == null) {
            return null;
        }
        return new SubGoal(active.id(), active.content(), position, total, done,
                active.doneWhen() == null ? "" : active.doneWhen(),
                active.dependsOn(), active.status() == Status.awaiting_confirm);
    }

    public record SubGoal(int id, String text, int position, int total, int done,
                          String doneWhen, List<Integer> dependsOn, boolean awaitingConfirm) {
        public SubGoal {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }

        public SubGoal(String text, int position, int total, int done) {
            this(0, text, position, total, done, "", List.of(), false);
        }

        public SubGoal(int id, String text, int position, int total, int done, String doneWhen) {
            this(id, text, position, total, done, doneWhen, List.of(), false);
        }

        public SubGoal(int id, String text, int position, int total, int done,
                       String doneWhen, List<Integer> dependsOn) {
            this(id, text, position, total, done, doneWhen, dependsOn, false);
        }
    }

    public synchronized String renderUpstreamEvidence(String sessionId) {
        List<TodoItem> list = get(sessionId);
        StringBuilder sb = new StringBuilder();
        for (TodoItem it : list) {
            if (it.status() != Status.completed) {
                continue;
            }
            sb.append("- #").append(it.id()).append(' ').append(it.content());
            if (it.evidence() != null && !it.evidence().isBlank()) {
                sb.append(" | evidence=").append(truncate(it.evidence(), 240));
            }
            if (it.validationHash() != null && !it.validationHash().isBlank()) {
                sb.append(" | validation_hash=").append(it.validationHash());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public synchronized String buildBlockedReport(String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务已挂起 / BLOCKED】存在失败子任务，禁止继续装作完成。\n");
        sb.append("请人工介入、todo reopen 后重试；不要编造结果。\n\n");
        for (TodoItem it : get(sessionId)) {
            if (it.status() != Status.blocked) {
                continue;
            }
            sb.append("- BLOCKED #").append(it.id()).append(' ').append(it.content());
            if (it.note() != null && !it.note().isBlank()) {
                sb.append("\n  原因：").append(it.note());
            }
            sb.append('\n');
        }
        sb.append('\n').append(render(sessionId));
        return sb.toString();
    }

    public synchronized String render(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 当前任务计划 (todo)\n");
        // 清单归属由模型表达，不由框架推断：目标变了就必须显式换清单。
        // 这句话是「换任务」的唯一出口 —— 没有它，模型只能沿用旧清单，
        // 于是 UI 上会出现「新任务配着上一个任务的计划」。
        sb.append("清单归属当前任务。若本轮目标与下面任何一条都不符，"
                + "必须先 todo action=clear 清空、或 action=set 重写清单，再开始新目标；"
                + "不要沿用旧清单声称在推进。\n");
        sb.append("已存在的子任务和状态：\n");
        for (TodoItem it : list) {
            sb.append("- [").append(symbol(it.status())).append("] #")
                    .append(it.id()).append(' ')
                    .append(it.content());
            if (it.dependsOn() != null && !it.dependsOn().isEmpty()) {
                sb.append(" | depends_on=").append(it.dependsOn());
            }
            if (it.doneWhen() != null && !it.doneWhen().isBlank()) {
                sb.append(" | done_when=").append(it.doneWhen());
            }
            if (it.note() != null && !it.note().isBlank()) {
                sb.append("（备注：").append(it.note()).append('）');
            }
            if (it.evidence() != null && !it.evidence().isBlank()) {
                sb.append("（证据：").append(it.evidence()).append('）');
            }
            if (it.validationHash() != null && !it.validationHash().isBlank()) {
                sb.append("（hash：").append(it.validationHash()).append('）');
            }
            sb.append('\n');
        }
        sb.append("\n执行原则：\n");
        sb.append("- 只聚焦依赖已满足的当前子目标；depends_on 未完成禁止推进\n");
        if (PermissionContext.confirmPolicy() != ConfirmPolicy.AUTO) {
            sb.append("- 上线/删除等危险步进入 awaiting_confirm，须用户在页面确认\n");
        }
        sb.append("- completed 需存在性 + 可插拔语义校验；上游 hash 变化须先 reopen\n");
        sb.append("- 工具连败 → blocked；可用 todo(action=reopen) 回滚并级联下游\n");
        sb.append("- pending/in_progress 未完成前禁止最终收尾\n");
        sb.append("- 缺密钥或须用户提供信息：update awaiting_confirm，向用户提问并等待\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Status parseStatus(String s) {
        try {
            return Status.valueOf(s.trim().toLowerCase());
        } catch (Exception e) {
            return Status.pending;
        }
    }

    private static String symbol(Status status) {
        return switch (status) {
            case completed -> "x";
            case in_progress -> "~";
            case awaiting_confirm -> "?";
            case cancelled -> "-";
            case blocked -> "!";
            default -> " ";
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * 任务级存储键。
     *
     * <p>返回 {@code TaskScope.scopeKey()}（{@code sessionId} 或 {@code sessionId#n}），
     * <b>不是</b> sessionId。所有 Map 操作都以它为准，因此「上一个任务的清单」
     * 与「当前任务的清单」落在两个互不可见的键上 —— 不需要搬、也不需要判断
     * 「这一轮算不算换任务」。</p>
     *
     * <p>没有 {@link com.miniagent.agent.scope.TaskScopeRegistry} 时（纯单测、
     * 无 Spring 场景）退回 sessionId，等价于「一个会话只有一个任务」。</p>
     */
    private String safeKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        String raw = sessionId.trim();
        return scopeRegistry == null ? raw : scopeRegistry.scopeKey(raw);
    }

    /** 作用域键 → 会话标识：{@code s_abc#3 → s_abc}，{@code s_abc → s_abc}。 */
    static String sessionOf(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        int i = key.lastIndexOf('#');
        if (i <= 0 || i == key.length() - 1) {
            return key;
        }
        for (int j = i + 1; j < key.length(); j++) {
            if (!Character.isDigit(key.charAt(j))) {
                return key;
            }
        }
        return key.substring(0, i);
    }

    /** 与 {@link com.miniagent.agent.scope.TaskScope#scopeKey()} 同口径。 */
    private static String scopeKeyOf(String sessionId, long taskId) {
        return taskId == 0L ? sessionId : sessionId + "#" + taskId;
    }

    private static long taskIdOf(String scopeKey, String sessionId) {
        if (scopeKey == null || sessionId == null || scopeKey.equals(sessionId)) {
            return 0L;
        }
        String tail = scopeKey.substring(sessionId.length() + 1);
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Path persistFile(String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return persistDir.resolve(safe + ".json");
    }

    private static long fileMtime(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 从一个会话的持久化行里加载<b>全部任务分区</b>。
     *
     * <p>行是按 sessionId 存的，行内的 {@code active_json} 是一个
     * {@code {"0":[...],"1":[...]}} 形态的分区对象；旧数据是裸数组，
     * 读取时整体归 0 号任务（{@link #parsePartitions}）。内存里则按
     * {@code scopeKey} 拆成多份，业务方法只看到自己那一份。</p>
     */
    private void ensureLoaded(String key) {
        String sessionId = sessionOf(key);
        if (useDb()) {
            try {
                SessionTodoPersistence.State st = persistence.load(sessionId);
                Map<Long, Partition> parts = parsePartitions(st.activeJson());
                // DB 空、本地文件有数据 → 一次性迁入。
                // 只迁 active：suspended_json 是旧机制「自动捞回」的残留，读回来就是重犯。
                if (parts.isEmpty() && Files.exists(persistFile(sessionId))) {
                    parts = parsePartitions(
                            Files.readString(persistFile(sessionId), StandardCharsets.UTF_8));
                    applyPartitions(sessionId, parts);
                    persist(key, todos.getOrDefault(key, new ArrayList<>()));
                    return;
                }
                applyPartitions(sessionId, parts);
            } catch (Exception e) {
                log.warn("从 DB 加载 todo 失败 {}: {}", sessionId, e.getMessage());
            }
            todos.putIfAbsent(key, new ArrayList<>());
            return;
        }
        Path file = persistFile(sessionId);
        long mtime = fileMtime(file);
        Long cached = fileMtimes.get(sessionId);
        if (cached != null && Objects.equals(cached, mtime) && todos.containsKey(key)) {
            return;
        }
        if (!Files.exists(file)) {
            todos.putIfAbsent(key, new ArrayList<>());
            fileMtimes.put(sessionId, -1L);
            return;
        }
        try {
            applyPartitions(sessionId, parsePartitions(
                    Files.readString(file, StandardCharsets.UTF_8)));
            fileMtimes.put(sessionId, mtime);
        } catch (Exception e) {
            log.warn("加载 todo 失败 {}: {}", file, e.getMessage());
            fileMtimes.put(sessionId, -1L);
        }
        todos.putIfAbsent(key, new ArrayList<>());
    }

    /**
     * 把「任务号 → 分区」摊进内存 map，键统一成 scopeKey。
     *
     * <p>作废的分区进 {@link #archived}，不进 {@link #todos} ——
     * 这样所有既有读取路径（注入、UI、统计）天然看不到它，不需要逐处加判断。</p>
     */
    private void applyPartitions(String sessionId, Map<Long, Partition> parts) {
        parts.forEach((taskId, partition) -> {
            String key = scopeKeyOf(sessionId, taskId);
            if (partition.archived()) {
                archived.put(key, new ArrayList<>(partition.items()));
                todos.remove(key);
            } else {
                todos.put(key, new ArrayList<>(partition.items()));
                archived.remove(key);
            }
        });
    }

    private List<TodoItem> readItems(Path file) throws Exception {
        return parseItemsJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    private List<TodoItem> parseItemsJson(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        return toItems(MAPPER.readValue(json, new TypeReference<List<PersistedItem>>() {}));
    }

    private static List<TodoItem> toItems(List<PersistedItem> raw) {
        List<TodoItem> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        for (PersistedItem p : raw) {
            if (p == null || p.content == null || p.content.isBlank()) {
                continue;
            }
            List<Integer> deps = p.dependsOn == null ? List.of() : List.copyOf(p.dependsOn);
            items.add(new TodoItem(p.id, p.content, parseStatus(p.status),
                    nullToEmpty(p.note), nullToEmpty(p.doneWhen), nullToEmpty(p.evidence),
                    nullToEmpty(p.validationHash), deps));
        }
        return items;
    }

    /**
     * 解析一个会话行里的清单。
     *
     * <p>兼容三种形态，都不需要 DDL 变更或一次性迁移：</p>
     * <ul>
     *   <li>旧：裸数组 {@code [...]} —— 整行属于 0 号任务，未作废；</li>
     *   <li>分区数组：{@code {"0":[...],"1":[...]}}；</li>
     *   <li>作废分区：{@code {"0":{"archived":true,"items":[...]}}} ——
     *       内容保留，但当前任务不使用。</li>
     * </ul>
     */
    private Map<Long, Partition> parsePartitions(String json) {
        Map<Long, Partition> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json.trim());
            if (root == null || root.isNull()) {
                return out;
            }
            if (root.isArray()) {
                List<TodoItem> items = toItems(MAPPER.convertValue(root,
                        new TypeReference<List<PersistedItem>>() {}));
                if (!items.isEmpty()) {
                    out.put(0L, new Partition(items, false));
                }
                return out;
            }
            if (!root.isObject()) {
                return out;
            }
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                long taskId = parseTaskIdKey(entry.getKey());
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    continue;
                }
                if (value.isArray()) {
                    List<TodoItem> items = toItems(MAPPER.convertValue(value,
                            new TypeReference<List<PersistedItem>>() {}));
                    if (!items.isEmpty()) {
                        out.put(taskId, new Partition(items, false));
                    }
                } else if (value.isObject()) {
                    boolean isArchived = value.path("archived").asBoolean(false);
                    JsonNode arr = value.get("items");
                    List<TodoItem> items = arr == null || arr.isNull()
                            ? List.of()
                            : toItems(MAPPER.convertValue(arr,
                                    new TypeReference<List<PersistedItem>>() {}));
                    if (!items.isEmpty()) {
                        out.put(taskId, new Partition(items, isArchived));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 todo 分区失败: {}", e.getMessage());
        }
        return out;
    }

    private static long parseTaskIdKey(String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static List<PersistedItem> toPersisted(List<TodoItem> items) {
        List<PersistedItem> raw = new ArrayList<>();
        if (items == null) {
            return raw;
        }
        for (TodoItem it : items) {
            PersistedItem p = new PersistedItem();
            p.id = it.id();
            p.content = it.content();
            p.status = it.status().name();
            p.note = it.note();
            p.doneWhen = it.doneWhen();
            p.evidence = it.evidence();
            p.validationHash = it.validationHash();
            p.dependsOn = it.dependsOn() == null ? List.of() : new ArrayList<>(it.dependsOn());
            raw.add(p);
        }
        return raw;
    }

    private String itemsToJson(List<TodoItem> items) throws Exception {
        return MAPPER.writeValueAsString(toPersisted(items));
    }

    /**
     * 把一个会话的全部任务分区序列化成整行 JSON。
     *
     * <p>遍历内存里所有 {@code belongsTo(sessionId)} 的键，拼成
     * {@code {"任务号": [条目...]}}。空分区不写，避免行里堆积 {@code "3":[]}。</p>
     */
    private String partitionsToJson(String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        collectPartitions(sessionId, todos, out, false);
        collectPartitions(sessionId, archived, out, true);
        trimPartitions(sessionId, out);
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("序列化 todo 分区失败 {}: {}", sessionId, e.getMessage());
            return "{}";
        }
    }

    /**
     * 收集某一类分区。
     *
     * <p>未作废的分区写成裸数组（与历史格式一致）；已作废的写成
     * {@code {"archived":true,"items":[...]}} —— 内容还在，只是当前任务不用它。
     * 这样旧版本读到作废分区时会当成普通清单，属于可接受降级（它至少不会丢数据）。</p>
     */
    private void collectPartitions(String sessionId,
                                   Map<String, List<TodoItem>> source,
                                   Map<String, Object> out,
                                   boolean archivedFlag) {
        for (Map.Entry<String, List<TodoItem>> e : source.entrySet()) {
            if (!com.miniagent.agent.scope.TaskScope.belongsTo(e.getKey(), sessionId)) {
                continue;
            }
            List<TodoItem> items = e.getValue();
            if (items == null || items.isEmpty()) {
                continue;
            }
            String taskId = String.valueOf(taskIdOf(e.getKey(), sessionId));
            if (archivedFlag) {
                Map<String, Object> box = new LinkedHashMap<>();
                box.put("archived", true);
                box.put("items", toPersisted(items));
                out.put(taskId, box);
            } else {
                out.put(taskId, toPersisted(items));
            }
        }
    }

    /**
     * 分区数超限时按任务号升序丢弃最早的分区，当前任务永不丢。
     *
     * <p>丢掉的同步从内存移除，避免内存与库里不一致（下次读会以为它还在）。</p>
     */
    private void trimPartitions(String sessionId, Map<String, Object> out) {
        if (out.size() <= MAX_TASK_PARTITIONS) {
            return;
        }
        List<String> keys = new ArrayList<>(out.keySet());
        keys.sort(java.util.Comparator.comparingLong(k -> {
            try {
                return Long.parseLong(k);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }));
        String current = String.valueOf(taskIdOf(safeKey(sessionId), sessionId));
        while (out.size() > MAX_TASK_PARTITIONS) {
            String dropped = null;
            for (String k : keys) {
                if (!k.equals(current) && out.containsKey(k)) {
                    dropped = k;
                    break;
                }
            }
            if (dropped == null) {
                return;
            }
            keys.remove(dropped);
            out.remove(dropped);
            String droppedKey = scopeKeyOf(sessionId, Long.parseLong(dropped));
            todos.remove(droppedKey);
            archived.remove(droppedKey);
            log.info("todo 分区超限，丢弃最早的任务 session={} taskId={}", sessionId, dropped);
        }
    }

    private void writeItems(Path file, List<TodoItem> items) throws Exception {
        Files.writeString(file,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(itemsToJson(items))),
                StandardCharsets.UTF_8);
    }

    /**
     * 写入一个任务的清单。
     *
     * <p>落盘的最小单位是<b>会话行</b>，不是任务：这一行的 {@code active_json} 里
     * 装着该会话所有任务的分区。所以这里先把内存里这个 key 的清单换掉，
     * 再把整行重新序列化。{@code suspended_json} 恒写 null（挂起机制已删除，
     * 列保留不删以免再加一次迁移）。</p>
     */
    private void persist(String key, List<TodoItem> items) {
        List<TodoItem> safe = items == null ? List.of() : items;
        todos.put(key, new ArrayList<>(safe));
        // 重新写入清单 = 撤销作废标记，否则同一任务会同时出现在两个 map 里
        archived.remove(key);
        persistRow(sessionOf(key));
        // UI 按 sessionId 订阅；这里不能传作用域键，否则前端收不到
        publishTodoUi(sessionOf(key), safe);
    }

    /** 把整个会话行（含未作废与已作废两类分区）落盘。 */
    private void persistRow(String sessionId) {
        String payload = partitionsToJson(sessionId);
        try {
            if (useDb()) {
                persistence.save(sessionId, payload, null);
            } else {
                Files.createDirectories(persistDir);
                Path file = persistFile(sessionId);
                Files.writeString(file,
                        MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(MAPPER.readTree(payload)),
                        StandardCharsets.UTF_8);
                fileMtimes.put(sessionId, fileMtime(file));
            }
        } catch (Exception e) {
            log.warn("持久化 todo 失败 {}: {}", sessionId, e.getMessage());
        }
    }

    /** SSE：把当前计划列表推给前端（完成项置灰勾选）。 */
    private void publishTodoUi(String sessionId, List<TodoItem> items) {
        if (eventCenter == null) {
            return;
        }
        try {
            List<Map<String, Object>> payload = new ArrayList<>(items.size());
            for (TodoItem it : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", it.id());
                row.put("content", it.content());
                row.put("status", it.status().name());
                row.put("note", it.note());
                row.put("evidence", it.evidence());
                payload.add(row);
            }
            eventCenter.publishTodo(sessionId, payload);
        } catch (Exception e) {
            log.debug("todo UI 推送跳过: {}", e.getMessage());
        }
    }

    /**
     * 在新一轮聊天 SSE 建立后重放当前作用域的任务清单。
     *
     * <p>清单可能来自上一轮或持久化存储；若本轮没有调用 todo 工具，新的 SSE 通道
     * 不会收到任何 {@code todo} 事件，导致右侧检查器有任务、聊天气泡却没有进度卡片。</p>
     */
    public synchronized void publishCurrentState(String sessionId) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        publishTodoUi(sessionOf(key), todos.getOrDefault(key, List.of()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static class PersistedItem {
        public int id;
        public String content;
        public String status;
        public String note;
        public String doneWhen;
        public String evidence;
        public String validationHash;
        public List<Integer> dependsOn;
    }

    public synchronized Map<String, Object> stats(String sessionId) {
        List<TodoItem> list = get(sessionId);
        int pending = 0, inProg = 0, awaiting = 0, done = 0, cancelled = 0, blocked = 0;
        for (TodoItem item : list) {
            switch (item.status()) {
                case pending -> pending++;
                case in_progress -> inProg++;
                case awaiting_confirm -> awaiting++;
                case completed -> done++;
                case cancelled -> cancelled++;
                case blocked -> blocked++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", list.size());
        m.put("pending", pending);
        m.put("in_progress", inProg);
        m.put("awaiting_confirm", awaiting);
        m.put("completed", done);
        m.put("cancelled", cancelled);
        m.put("blocked", blocked);
        m.put("incomplete", pending + inProg + awaiting);
        return m;
    }
}
