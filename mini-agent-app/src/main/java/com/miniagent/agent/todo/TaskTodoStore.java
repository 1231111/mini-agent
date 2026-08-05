package com.miniagent.agent.todo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.intent.TaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Agent 循环复用的 Todo 列表。
 * 每个 sessionId 一份；内存 + 磁盘（workspace/.todos/）双写，进程重启可恢复。
 */
@Component
public class TaskTodoStore {

    private static final Logger log = LoggerFactory.getLogger(TaskTodoStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Status { pending, in_progress, completed, cancelled }

    /**
     * @param doneWhen 验收标准，如 file_exists:workspace/x.md / media_delivered / note_required
     * @param evidence 完成证据（路径、命令结果摘要等）
     */
    public record TodoItem(int id, String content, Status status, String note,
                           String doneWhen, String evidence) {
        public TodoItem(int id, String content, Status status, String note) {
            this(id, content, status, note, "", "");
        }
    }

    private final Map<String, List<TodoItem>> todos = new ConcurrentHashMap<>();
    private final Path persistDir;

    public TaskTodoStore(@Value("${agent.todo.persist-dir:./workspace/.todos}") String persistDir) {
        this.persistDir = Path.of(persistDir).toAbsolutePath().normalize();
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

    /** 是否还有未完成（pending / in_progress）项 */
    public synchronized boolean hasIncomplete(String sessionId) {
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.pending || it.status() == Status.in_progress) return true;
        }
        return false;
    }

    public synchronized boolean allTerminal(String sessionId) {
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) return true;
        for (TodoItem it : list) {
            if (it.status() != Status.completed && it.status() != Status.cancelled) return false;
        }
        return true;
    }

    /** 计划中像「批量写文件/多模块」的未完成步数 */
    public synchronized int countBatchablePending(String sessionId) {
        int n = 0;
        for (TodoItem it : get(sessionId)) {
            if (it.status() == Status.completed || it.status() == Status.cancelled) continue;
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
            for (Map<String, Object> raw : rawItems) {
                if (raw == null) continue;
                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                if (content.isEmpty()) continue;
                Status status = parseStatus(String.valueOf(raw.getOrDefault("status", "pending")));
                String note = str(raw.get("note"));
                String doneWhen = str(raw.getOrDefault("done_when", raw.get("doneWhen")));
                String evidence = str(raw.get("evidence"));
                int id = raw.get("id") instanceof Number n ? n.intValue() : autoId;
                if (id <= 0) id = autoId;

                for (TodoItem old : prev) {
                    if (old.id() == id && old.content().equals(content)
                            && rank(old.status()) > rank(status)) {
                        status = old.status();
                        if (note.isEmpty()) note = old.note();
                        if (doneWhen.isEmpty()) doneWhen = old.doneWhen();
                        if (evidence.isEmpty()) evidence = old.evidence();
                        break;
                    }
                }

                // 首项自动 in_progress
                if (items.isEmpty() && status == Status.pending) {
                    status = Status.in_progress;
                }

                items.add(new TodoItem(id, content, status, note, doneWhen, evidence));
                autoId = Math.max(autoId, id) + 1;
            }
        }
        todos.put(key, items);
        persist(key, items);
        return new ArrayList<>(items);
    }

    private static int rank(Status s) {
        return switch (s) {
            case completed -> 3;
            case in_progress -> 2;
            case cancelled -> 1;
            case pending -> 0;
        };
    }

    /**
     * 更新状态。若标 completed 且有 done_when，则校验 evidence / 客观条件。
     * @return 成功时返回列表；失败时返回 null，errorOut[0] 写错误信息
     */
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
                newEvidence = newNote; // 允许用 note 充当证据
            }

            if (status == Status.completed) {
                String check = verifyCompletion(it.doneWhen(), newEvidence);
                if (check != null) {
                    if (errorOut != null && errorOut.length > 0) errorOut[0] = check;
                    return null;
                }
            }

            list.set(i, new TodoItem(it.id(), it.content(), status, newNote, it.doneWhen(), newEvidence));

            // 完成一项后，自动把下一个 pending 置为 in_progress
            if (status == Status.completed) {
                for (int j = 0; j < list.size(); j++) {
                    TodoItem next = list.get(j);
                    if (next.status() == Status.pending) {
                        list.set(j, new TodoItem(next.id(), next.content(), Status.in_progress,
                                next.note(), next.doneWhen(), next.evidence()));
                        break;
                    }
                }
            }
            persist(key, list);
            return new ArrayList<>(list);
        }
        if (errorOut != null && errorOut.length > 0) errorOut[0] = "未找到 id=" + id + " 的子任务";
        return null;
    }

    /** 兼容旧调用 */
    public synchronized List<TodoItem> update(String sessionId, int id, String statusStr, String note) {
        String[] err = new String[1];
        List<TodoItem> r = update(sessionId, id, statusStr, note, null, err);
        return r != null ? r : get(sessionId);
    }

    /**
     * 校验完成条件。返回 null 表示通过，否则为错误信息。
     * 支持：
     *   - file_exists:相对或绝对路径
     *   - media_delivered（evidence 含 markdown 图或 http 图链）
     *   - note_required / evidence_required（非空即可）
     *   - 空 done_when：要求至少有非空 evidence/note
     */
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
                // 也接受 evidence 里直接给了存在的路径
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
        // 未知 done_when：至少要求 evidence 非空
        return ev.isBlank() ? "标记 completed 时必须提供 evidence（对照 done_when=" + dw + "）" : null;
    }

    private static Path resolvePath(String path) {
        String normalized = path.replace('\\', '/').trim();
        Path p = Path.of(normalized);
        if (p.isAbsolute()) return p.normalize();
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve(normalized).normalize();
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
        boolean firstActive = false;
        for (TaskStep step : steps) {
            if (step == null || step.goal() == null || step.goal().isBlank()) continue;
            Status status = firstActive ? Status.pending : Status.in_progress;
            firstActive = true;
            int id = step.id() > 0 ? step.id() : items.size() + 1;
            items.add(new TodoItem(id, step.goal().trim(), status, "", "note_required", ""));
        }
        if (items.isEmpty()) return false;
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
        List<TodoItem> list = get(sessionId);
        if (list.isEmpty()) return null;
        int total = list.size();
        int done = 0;
        TodoItem active = null;
        int position = 0;
        for (int i = 0; i < list.size(); i++) {
            TodoItem item = list.get(i);
            if (item.status() == Status.completed) done++;
            if (active == null && item.status() == Status.in_progress) { active = item; position = i + 1; }
        }
        if (active == null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).status() == Status.pending) { active = list.get(i); position = i + 1; break; }
            }
        }
        if (active == null) return null;
        return new SubGoal(active.content(), position, total, done,
                active.doneWhen() == null ? "" : active.doneWhen());
    }

    public record SubGoal(String text, int position, int total, int done, String doneWhen) {
        public SubGoal(String text, int position, int total, int done) {
            this(text, position, total, done, "");
        }
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
            if (it.doneWhen() != null && !it.doneWhen().isBlank()) {
                sb.append(" | done_when=").append(it.doneWhen());
            }
            if (it.note() != null && !it.note().isBlank()) {
                sb.append("（备注：").append(it.note()).append('）');
            }
            if (it.evidence() != null && !it.evidence().isBlank()) {
                sb.append("（证据：").append(it.evidence()).append('）');
            }
            sb.append('\n');
        }
        sb.append("\n执行原则：\n");
        sb.append("- 只聚焦第一个 pending/in_progress 子任务（当前子目标）\n");
        sb.append("- 完成后立刻 todo update 标 completed，并提供 evidence（对照 done_when）\n");
        sb.append("- 未全部 completed/cancelled 前禁止最终收尾\n");
        sb.append("- 可独立的多步产出优先 delegate_task 并行\n");
        return sb.toString();
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
            case cancelled -> "-";
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
                    items.add(new TodoItem(p.id, p.content, parseStatus(p.status),
                            nullToEmpty(p.note), nullToEmpty(p.doneWhen), nullToEmpty(p.evidence)));
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

    /** Jackson DTO for disk format */
    public static class PersistedItem {
        public int id;
        public String content;
        public String status;
        public String note;
        public String doneWhen;
        public String evidence;
    }

    public synchronized Map<String, Object> stats(String sessionId) {
        List<TodoItem> list = get(sessionId);
        int pending = 0, inProg = 0, done = 0, cancelled = 0;
        for (TodoItem item : list) {
            switch (item.status()) {
                case pending -> pending++;
                case in_progress -> inProg++;
                case completed -> done++;
                case cancelled -> cancelled++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", list.size());
        m.put("pending", pending);
        m.put("in_progress", inProg);
        m.put("completed", done);
        m.put("cancelled", cancelled);
        m.put("incomplete", pending + inProg);
        return m;
    }
}
