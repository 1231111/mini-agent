package com.miniagent.agent.todo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.intent.TaskStep;
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

    /** 多依赖或关键交付类步骤：进入执行前必须 confirm */
    private static final java.util.regex.Pattern CRITICAL_GOAL = java.util.regex.Pattern.compile(
            "(?i)(最终|交付|上线|发布|汇总|合并验收|关键路径|生产环境|正式提交)");

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
    private final Path persistDir;
    private final List<TodoStepValidator> validators;

    /** 测试 / 无 Spring 场景 */
    public TaskTodoStore(String persistDir) {
        this(persistDir, List.of(new BuiltinSemanticTodoValidator()));
    }

    @Autowired
    public TaskTodoStore(@Value("${agent.todo.persist-dir:./workspace/.todos}") String persistDir,
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
                    || it.status() == Status.awaiting_confirm) return true;
        }
        return false;
    }

    public synchronized boolean hasAwaitingConfirm(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.awaiting_confirm) return true;
        }
        return false;
    }

    public synchronized TodoItem awaitingConfirmItem(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.awaiting_confirm) return it;
        }
        return null;
    }

    public synchronized boolean hasBlocked(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.blocked) return true;
        }
        return false;
    }

    public synchronized boolean canSucceed(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) return true;
        for (TodoItem it : list) {
            if (it.status() == Status.blocked) return false;
            if (it.status() != Status.completed && it.status() != Status.cancelled) return false;
        }
        return true;
    }

    public synchronized boolean allTerminal(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) return true;
        for (TodoItem it : list) {
            if (it.status() != Status.completed && it.status() != Status.cancelled
                    && it.status() != Status.blocked) return false;
        }
        return true;
    }

    public synchronized int countBatchablePending(String sessionId) {
        int n = 0;
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.completed || it.status() == Status.cancelled
                    || it.status() == Status.blocked) continue;
            String c = it.content() == null ? "" : it.content();
            if (c.matches("(?s).*(写|生成|实现|模块|文件|接口|页面|文档).*")) n++;
        }
        return n;
    }

    public synchronized List<TodoItem> set(String sessionId, List<Map<String, Object>> rawItems) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> prev = todos.getOrDefault(key, Collections.emptyList());
        List<TodoItem> items = new ArrayList<>();
        if (rawItems != null) {
            int autoId = 1;
            Integer prevId = null;
            for (Map<String, Object> raw : rawItems) {
                if (raw == null) continue;
                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                if (content.isEmpty()) continue;
                Status status = parseStatus(String.valueOf(raw.getOrDefault("status", "pending")));
                String note = str(raw.get("note"));
                String doneWhen = str(raw.getOrDefault("done_when", raw.get("doneWhen")));
                String evidence = str(raw.get("evidence"));
                String hash = str(raw.getOrDefault("validation_hash", raw.get("validationHash")));
                int id = raw.get("id") instanceof Number n ? n.intValue() : autoId;
                if (id <= 0) id = autoId;

                boolean depsSpecified = raw.containsKey("depends_on") || raw.containsKey("dependsOn");
                List<Integer> deps = parseDependsOn(raw.getOrDefault("depends_on", raw.get("dependsOn")));
                if (!depsSpecified && prevId != null) {
                    deps = List.of(prevId); // 默认串行依赖上一节，防脏数据传染
                }

                for (TodoItem old : prev) {
                    if (old.id() == id && old.content().equals(content)
                            && rank(old.status()) > rank(status)) {
                        status = old.status();
                        if (note.isEmpty()) note = old.note();
                        if (doneWhen.isEmpty()) doneWhen = old.doneWhen();
                        if (evidence.isEmpty()) evidence = old.evidence();
                        if (hash.isEmpty()) hash = old.validationHash();
                        if (!depsSpecified && !old.dependsOn().isEmpty()) deps = old.dependsOn();
                        break;
                    }
                }

                items.add(new TodoItem(id, content, status, note, doneWhen, evidence, hash, deps));
                prevId = id;
                autoId = Math.max(autoId, id) + 1;
            }
        }
        // 仅将「依赖已满足」的第一项标为 in_progress
        promoteReadyItems(items);
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

    static boolean needsConfirmGate(TodoItem it) {
        if (it == null) return false;
        if (it.dependsOn() != null && it.dependsOn().size() > 2) return true;
        String c = it.content() == null ? "" : it.content();
        return CRITICAL_GOAL.matcher(c).find();
    }

    public synchronized List<TodoItem> update(String sessionId, int id, String statusStr,
                                              String note, String evidence, String[] errorOut) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) continue;
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
                    if (errorOut != null && errorOut.length > 0) errorOut[0] = depErr;
                    return null;
                }
            }
            // 禁止跳过 CONFIRM：关键步不能直接从 pending 改成 in_progress
            if (status == Status.in_progress && it.status() == Status.pending && needsConfirmGate(it)) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "关键步骤须先 todo(action=confirm, id=" + id + ")，不能直接 in_progress";
                }
                return null;
            }
            if (status == Status.in_progress && it.status() == Status.awaiting_confirm) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "请使用 todo(action=confirm, id=" + id + ")，不要用 update 绕过确认门禁";
                }
                return null;
            }

            if (status == Status.completed) {
                String check = verifyCompletion(it.doneWhen(), newEvidence);
                if (check != null) {
                    if (errorOut != null && errorOut.length > 0) errorOut[0] = check;
                    return null;
                }
                String pluginErr = runValidators(it, newEvidence);
                if (pluginErr != null) {
                    if (errorOut != null && errorOut.length > 0) errorOut[0] = pluginErr;
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
        if (errorOut != null && errorOut.length > 0) errorOut[0] = "未找到 id=" + id + " 的子任务";
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
            if (errorOut != null && errorOut.length > 0) errorOut[0] = "未找到 id=" + id;
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
     * 关键路径确认：将 awaiting_confirm → in_progress，允许开始执行。
     */
    public synchronized List<TodoItem> confirm(String sessionId, int id, String note, String[] errorOut) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) continue;
            if (it.status() != Status.awaiting_confirm) {
                if (errorOut != null && errorOut.length > 0) {
                    errorOut[0] = "仅 awaiting_confirm 可 confirm，当前=" + it.status();
                }
                return null;
            }
            String depErr = dependencyGate(list, it);
            if (depErr != null) {
                if (errorOut != null && errorOut.length > 0) errorOut[0] = depErr;
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
        if (errorOut != null && errorOut.length > 0) errorOut[0] = "未找到 id=" + id;
        return null;
    }

    public synchronized boolean markBlocked(String sessionId, int id, String reason) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() != id) continue;
            if (it.status() == Status.completed || it.status() == Status.cancelled) return false;
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
        if (sg == null) return null;
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
                return "[" + v.name() + "] 校验异常: " + e.getMessage();
            }
        }
        return null;
    }

    /** 依赖未完成或上游产物指纹失效 → 错误信息 */
    String dependencyGate(List<TodoItem> list, TodoItem it) {
        if (it.dependsOn() == null || it.dependsOn().isEmpty()) return null;
        for (int depId : it.dependsOn()) {
            TodoItem dep = findById(list, depId);
            if (dep == null) return "依赖 #" + depId + " 不存在";
            if (dep.status() != Status.completed) {
                return "依赖未满足：#" + depId + " 状态=" + dep.status() + "（" + dep.content() + "）";
            }
            if (dep.validationHash() != null && !dep.validationHash().isBlank()) {
                TodoSemanticValidator.Result again = TodoSemanticValidator.validate(
                        dep.content(), dep.doneWhen(), dep.evidence());
                if (!again.ok()) {
                    return "上游产物失效：#" + depId + " " + again.error()
                            + "。请先 todo(action=reopen, id=" + depId + ")";
                }
                if (!dep.validationHash().equals(again.contentHash())) {
                    return "上游产物指纹不匹配：#" + depId + " 期望 hash="
                            + dep.validationHash() + " 实际=" + again.contentHash()
                            + "。请先 reopen #" + depId;
                }
            }
        }
        return null;
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
        if (hasActive) return;
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
        if (it.dependsOn() == null) return false;
        for (int depId : it.dependsOn()) {
            TodoItem dep = findById(list, depId);
            if (dep == null || dep.status() != Status.completed) return true;
        }
        return false;
    }

    private static TodoItem copyStatus(TodoItem it, Status status) {
        return new TodoItem(it.id(), it.content(), status, it.note(),
                it.doneWhen(), it.evidence(), it.validationHash(), it.dependsOn());
    }

    private static TodoItem findById(List<TodoItem> list, int id) {
        for (TodoItem it : list) {
            if (it.id() == id) return it;
        }
        return null;
    }

    private static Set<Integer> collectDependents(List<TodoItem> list, int rootId) {
        Set<Integer> out = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TodoItem it : list) {
                if (out.contains(it.id()) || it.id() == rootId) continue;
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
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> list) {
                List<Integer> ids = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Number n) ids.add(n.intValue());
                    else {
                        String s = String.valueOf(o).trim();
                        if (!s.isEmpty()) ids.add(Integer.parseInt(s));
                    }
                }
                return ids;
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty() || "none".equalsIgnoreCase(s) || "[]".equals(s)) return List.of();
            if (s.startsWith("[")) {
                List<Object> list = MAPPER.readValue(s, List.class);
                return parseDependsOn(list);
            }
            List<Integer> ids = new ArrayList<>();
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) ids.add(Integer.parseInt(part.trim()));
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    public String verifyCompletion(String doneWhen, String evidence) {
        String dw = doneWhen == null ? "" : doneWhen.trim();
        String ev = evidence == null ? "" : evidence.trim();

        if (dw.isBlank()) {
            if (ev.isBlank()) {
                return "标记 completed 时必须提供 evidence 或 note（说明完成证据，如文件路径）";
            }
            return null;
        }

        if (dw.startsWith("file_exists:")) {
            String path = dw.substring("file_exists:".length()).trim();
            if (path.isEmpty()) return "done_when 的 file_exists 路径为空";
            Path p = resolvePath(path);
            if (!Files.exists(p)) {
                if (!ev.isBlank() && Files.exists(resolvePath(ev))) return null;
                return "验收失败：文件不存在 " + path + "（可先 write_file 再 completed，或把实际路径写入 evidence）";
            }
            return null;
        }
        if ("media_delivered".equalsIgnoreCase(dw) || "media".equalsIgnoreCase(dw)) {
            if (ev.contains("![") || ev.contains("http://") || ev.contains("https://")
                    || ev.contains("/generated-images/") || ev.contains("/static/images/")) {
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

    private static Path resolvePath(String path) {
        String normalized = path.replace('\\', '/').trim();
        Path p = Path.of(normalized);
        if (p.isAbsolute()) return p.normalize();
        return com.miniagent.agent.tool.BuiltinTools.effectiveWorkspaceRoot().resolve(normalized).normalize();
    }

    public synchronized void clear(String sessionId) {
        String key = safeKey(sessionId);
        todos.remove(key);
        try {
            Files.deleteIfExists(persistFile(key));
        } catch (Exception e) {
            log.warn("删除 todo 文件失败: {}", e.getMessage());
        }
    }

    public synchronized boolean seedFromSteps(String sessionId, List<TaskStep> steps) {
        String key = safeKey(sessionId);
        ensureLoaded(key);
        List<TodoItem> existing = todos.get(key);
        if (existing != null && !existing.isEmpty()) return false;
        if (steps == null || steps.isEmpty()) return false;
        List<TodoItem> items = new ArrayList<>();
        Integer prevId = null;
        for (TaskStep step : steps) {
            if (step == null || step.goal() == null || step.goal().isBlank()) continue;
            int id = step.id() > 0 ? step.id() : items.size() + 1;
            List<Integer> deps = prevId == null ? List.of() : List.of(prevId);
            items.add(new TodoItem(id, step.goal().trim(), Status.pending, "",
                    "note_required", "", "", deps));
            prevId = id;
        }
        if (items.isEmpty()) return false;
        promoteReadyItems(items);
        todos.put(key, items);
        persist(key, items);
        return true;
    }

    public synchronized String currentSubGoal(String sessionId) {
        SubGoal sg = currentSubGoalDetail(sessionId);
        if (sg == null) return "";
        return "当前子目标 (#" + sg.position() + "/" + sg.total() + ")：" + sg.text();
    }

    public synchronized SubGoal currentSubGoalDetail(String sessionId) {
        List<TodoItem> list = new ArrayList<>(get(sessionId));
        if (list.isEmpty()) return null;
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
        if (active == null) return null;
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
            if (it.status() != Status.completed) continue;
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
            if (it.status() != Status.blocked) continue;
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
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("# 当前任务计划 (todo)\n");
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
        sb.append("- 关键步（依赖>2 或交付/上线类）进入 awaiting_confirm，须 todo(action=confirm)\n");
        sb.append("- completed 需存在性 + 可插拔语义校验；上游 hash 变化须先 reopen\n");
        sb.append("- 工具连败 → blocked；可用 todo(action=reopen) 回滚并级联下游\n");
        sb.append("- 未全部 completed/cancelled 前禁止最终收尾\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
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

    private static String safeKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    private Path persistFile(String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return persistDir.resolve(safe + ".json");
    }

    private void ensureLoaded(String key) {
        if (todos.containsKey(key)) return;
        Path file = persistFile(key);
        if (!Files.exists(file)) {
            todos.put(key, new ArrayList<>());
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<PersistedItem> raw = MAPPER.readValue(json, new TypeReference<>() {});
            List<TodoItem> items = new ArrayList<>();
            if (raw != null) {
                for (PersistedItem p : raw) {
                    if (p == null || p.content == null || p.content.isBlank()) continue;
                    List<Integer> deps = p.dependsOn == null ? List.of() : List.copyOf(p.dependsOn);
                    items.add(new TodoItem(p.id, p.content, parseStatus(p.status),
                            nullToEmpty(p.note), nullToEmpty(p.doneWhen), nullToEmpty(p.evidence),
                            nullToEmpty(p.validationHash), deps));
                }
            }
            todos.put(key, items);
        } catch (Exception e) {
            log.warn("加载 todo 失败 {}: {}", file, e.getMessage());
            todos.put(key, new ArrayList<>());
        }
    }

    private void persist(String key, List<TodoItem> items) {
        try {
            Files.createDirectories(persistDir);
            List<PersistedItem> raw = new ArrayList<>();
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
            Files.writeString(persistFile(key), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(raw),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("持久化 todo 失败 {}: {}", key, e.getMessage());
        }
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
