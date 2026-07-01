package com.miniagent.agent.todo;

import com.miniagent.agent.intent.TaskStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Agent 循环复用的 Todo 列表。
 * 每个 sessionId 一份，记录复杂任务的子步骤、状态、结果摘要。
 *
 * 设计思路对标 hermes-agent 的 todo_tool：
 *   - 模型自己写计划，自己更新进度
 *   - 每轮 system prompt 注入当前 todo 状态
 *   - 工具执行不消耗 conversation 上下文
 */
@Component
public class TaskTodoStore {

    public enum Status { pending, in_progress, completed, cancelled }

    public record TodoItem(int id, String content, Status status, String note) {}

    /** sessionId -> 顺序 Todo 列表 */
    private final Map<String, List<TodoItem>> todos = new ConcurrentHashMap<>();

    public synchronized List<TodoItem> get(String sessionId) {
        return new ArrayList<>(todos.getOrDefault(safeKey(sessionId), Collections.emptyList()));
    }

    /**
     * 整体覆写：模型用 todo.set 提交完整新计划。
     *
     * 幂等保护：若已存在同 id、同内容的子任务，且其旧状态比新提交的状态更靠前
     * （已有进度 completed/in_progress，新提交却是 pending），则保留旧状态与旧备注。
     * 这样可避免「模型中途又 set 一份全 pending 的同样计划，把已完成进度清零、
     * 进而反复重做触发死循环」的情形。
     */
    public synchronized List<TodoItem> set(String sessionId, List<Map<String, Object>> rawItems) {
        String key = safeKey(sessionId);
        List<TodoItem> prev = todos.getOrDefault(key, Collections.emptyList());
        List<TodoItem> items = new ArrayList<>();
        if (rawItems != null) {
            int autoId = 1;
            for (Map<String, Object> raw : rawItems) {
                if (raw == null) continue;
                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                if (content.isEmpty()) continue;
                Status status = parseStatus(String.valueOf(raw.getOrDefault("status", "pending")));
                String note = String.valueOf(raw.getOrDefault("note", ""));
                int id = raw.get("id") instanceof Number n ? n.intValue() : autoId;
                if (id <= 0) id = autoId;

                // 幂等保护：同 id 同内容时，保留更靠前的旧进度
                for (TodoItem old : prev) {
                    if (old.id() == id && old.content().equals(content)
                            && rank(old.status()) > rank(status)) {
                        status = old.status();
                        if (note.isEmpty()) note = old.note();
                        break;
                    }
                }

                items.add(new TodoItem(id, content, status, note));
                autoId = Math.max(autoId, id) + 1;
            }
        }
        todos.put(key, items);
        return new ArrayList<>(items);
    }

    /** 状态推进度：数字越大越靠后。用于幂等保护时判断「哪个状态更靠前」。 */
    private static int rank(Status s) {
        return switch (s) {
            case completed -> 3;
            case in_progress -> 2;
            case cancelled -> 1;
            case pending -> 0;
        };
    }

    /** 局部更新：根据 id 修改状态/note */
    public synchronized List<TodoItem> update(String sessionId, int id, String statusStr, String note) {
        String key = safeKey(sessionId);
        List<TodoItem> list = todos.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            TodoItem it = list.get(i);
            if (it.id() == id) {
                Status status = statusStr == null || statusStr.isBlank() ? it.status() : parseStatus(statusStr);
                String newNote = note == null || note.isBlank() ? it.note() : note;
                list.set(i, new TodoItem(it.id(), it.content(), status, newNote));
                break;
            }
        }
        return new ArrayList<>(list);
    }

    public synchronized void clear(String sessionId) {
        todos.remove(safeKey(sessionId));
    }

    /**
     * 用意图规划阶段产出的 {@link TaskStep} 列表播种 todo 栈。
     * 仅在该 session 还没有任何 todo 时播种（首项设 in_progress，其余 pending）；
     * 已有 todo（模型在对话中自己建过计划）则不覆盖，避免抹掉进度。
     *
     * @return 是否真正播种了（true=本次新建，false=已存在跳过）
     */
    public synchronized boolean seedFromSteps(String sessionId, List<TaskStep> steps) {
        String key = safeKey(sessionId);
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
            items.add(new TodoItem(id, step.goal().trim(), status, ""));
        }
        if (items.isEmpty()) return false;
        todos.put(key, items);
        return true;
    }

    /**
     * 返回当前活动子目标的紧凑指针，供框架每轮注入上下文 / 推送前端。
     * 活动项 = 第一个 in_progress，没有 in_progress 则取第一个 pending。
     * 无可执行子目标（空栈或全部完成）时返回空字符串。
     */
    public synchronized String currentSubGoal(String sessionId) {
        SubGoal sg = currentSubGoalDetail(sessionId);
        if (sg == null) return "";
        return "当前子目标 (#" + sg.position() + "/" + sg.total() + ")：" + sg.text();
    }

    /** 当前活动子目标的结构化信息，无则返回 null。 */
    public synchronized SubGoal currentSubGoalDetail(String sessionId) {
        List<TodoItem> list = todos.getOrDefault(safeKey(sessionId), Collections.emptyList());
        if (list.isEmpty()) return null;
        int total = list.size();
        long done = list.stream().filter(i -> i.status() == Status.completed).count();
        TodoItem active = null;
        int position = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).status() == Status.in_progress) { active = list.get(i); position = i + 1; break; }
        }
        if (active == null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).status() == Status.pending) { active = list.get(i); position = i + 1; break; }
            }
        }
        if (active == null) return null;  // 全部完成 / 取消
        return new SubGoal(active.content(), position, total, (int) done);
    }

    /** 当前子目标快照：文字 + 第几个/共几个 + 已完成数。 */
    public record SubGoal(String text, int position, int total, int done) {}

    /** 渲染当前 todo 清单为可读 markdown，注入 system prompt */
    public synchronized String render(String sessionId) {
        List<TodoItem> list = todos.getOrDefault(safeKey(sessionId), Collections.emptyList());
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("# 当前任务计划 (todo)\n");
        sb.append("已存在的子任务和状态：\n");
        for (TodoItem it : list) {
            sb.append("- [").append(symbol(it.status())).append("] #")
                    .append(it.id()).append(' ')
                    .append(it.content());
            if (it.note() != null && !it.note().isBlank()) {
                sb.append("（备注：").append(it.note()).append('）');
            }
            sb.append('\n');
        }
        sb.append("\n执行原则：\n");
        sb.append("- 选择第一个 pending 或 in_progress 的子任务作为下一步目标\n");
        sb.append("- 完成一个子任务后立刻调用 todo 工具把它标记为 completed\n");
        sb.append("- 不要重复执行已完成的子任务\n");
        sb.append("- 所有 completed 后再做最终回复\n");
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

    private static String safeKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    /** 给系统提示用的 metadata：是否有计划、待办数 */
    public synchronized Map<String, Object> stats(String sessionId) {
        List<TodoItem> list = todos.getOrDefault(safeKey(sessionId), Collections.emptyList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", list.size());
        long pending = list.stream().filter(i -> i.status() == Status.pending).count();
        long inProg  = list.stream().filter(i -> i.status() == Status.in_progress).count();
        long done    = list.stream().filter(i -> i.status() == Status.completed).count();
        m.put("pending", pending);
        m.put("in_progress", inProg);
        m.put("completed", done);
        return m;
    }
}
