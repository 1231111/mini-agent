package com.miniagent.agent.todo;

import com.miniagent.agent.core.SessionEventCenter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

class TaskTodoUiReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysExistingTaskItemsToNewChatStream() {
        SessionEventCenter events = mock(SessionEventCenter.class);
        TaskTodoStore store = new TaskTodoStore(tempDir.toString());
        store.setEventCenter(events);
        store.set("session-1", List.of(
                Map.of("id", 1, "content", "搜索今日 AI 新闻", "status", "completed",
                        "evidence", "找到 5 条来源", "note", "发布日期均为今日"),
                Map.of("id", 2, "content", "整理摘要", "status", "in_progress")));
        reset(events); // only assert the explicit replay, not the initial todo.set push

        store.publishCurrentState("session-1");

        ArgumentCaptor<List<Map<String, Object>>> payload = ArgumentCaptor.forClass(List.class);
        verify(events).publishTodo(org.mockito.ArgumentMatchers.eq("session-1"), payload.capture());
        assertEquals(2, payload.getValue().size());
        assertEquals("搜索今日 AI 新闻", payload.getValue().get(0).get("content"));
        assertEquals("completed", payload.getValue().get(0).get("status"));
        assertEquals("找到 5 条来源", payload.getValue().get(0).get("evidence"));
        assertEquals("发布日期均为今日", payload.getValue().get(0).get("note"));
        assertEquals("in_progress", payload.getValue().get(1).get("status"));
        assertTrue(payload.getValue().get(1).containsKey("evidence"));
    }
}
