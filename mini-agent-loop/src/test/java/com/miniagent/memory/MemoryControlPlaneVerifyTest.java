package com.miniagent.memory;

import com.miniagent.agent.context.ContextLoadPolicy;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.memory.model.MemoryReadPolicy;
import com.miniagent.memory.model.SemanticFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验收当前记忆控制面：上下文加载策略是否真正挡住 Blob，用户写入是否只落事实。
 */
class MemoryControlPlaneVerifyTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;
    private DefaultMemoryService service;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir);
        service = new DefaultMemoryService();
        ReflectionTestUtils.setField(service, "memoryStore", store);
        ReflectionTestUtils.setField(service, "memoryManager", null);
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
        MemoryStore.setCurrentUser(7L);
        MemoryStore.setCurrentTenant("t1");
        store.loadFromDisk();
        store.add("memory", "长期笔记：仓库用 JDK 21");
        store.updateMidtermMemory("中期摘要：上周在做报表项目");
        store.add("user", "沟通偏好：先结论后细节");
    }

    @AfterEach
    void tearDown() {
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
    }

    @Test
    void question策略不注入长期与中期blob() {
        MemoryReadPolicy policy = ContextLoadPolicy
                .forSignals(TaskSignals.parse("question")).memoryPolicy();
        String prompt = service.retrieveForPrompt("s1", "你好", policy);
        assertFalse(prompt.contains("JDK 21"), prompt);
        assertFalse(prompt.contains("报表项目"), prompt);
        assertTrue(prompt.contains("先结论后细节"), prompt);
    }

    /**
     * 没有任何信号时按动手轮加载，而不是整段清空。
     *
     * <p>旧实现里有一类「评审」意图会把记忆整段置空，随分类一起删掉了：
     * 清空是不可恢复的（模型看不到任何记忆），而多注入只是可恢复的噪音。
     * 现在只有 {@code lightTurn} 会削减记忆槽，其余一律按动手轮。</p>
     */
    @Test
    void 无信号时按动手轮加载而非清空() {
        MemoryReadPolicy policy = ContextLoadPolicy.forSignals(TaskSignals.NONE).memoryPolicy();
        String prompt = service.retrieveForPrompt("s1", "这图哪里不对", policy);
        assertTrue(prompt.contains("JDK 21"), prompt);
        assertTrue(prompt.contains("先结论后细节"), prompt);
        assertFalse(prompt.contains("报表项目"), prompt);
    }

    @Test
    void newTask不再注入遗留midterm() {
        MemoryReadPolicy policy = ContextLoadPolicy
                .forSignals(TaskSignals.parse("file")).memoryPolicy();
        String prompt = service.retrieveForPrompt("s1", "写一份方案", policy);
        assertTrue(prompt.contains("JDK 21"), prompt);
        assertFalse(prompt.contains("报表项目"), prompt);
        assertFalse(policy.midterm());
    }

    @Test
    void question无指代不打开长期记忆() {
        ContextLoadPolicy p = ContextLoadPolicy.forSignals(TaskSignals.parse("question"));
        assertFalse(p.injectMemory());
        assertFalse(p.injectMidterm());
        assertTrue(p.injectUser());
    }

    @Test
    void user写入走事实且不写画像blob() {
        MemoryManager manager = mock(MemoryManager.class);
        List<SemanticFact> written = new ArrayList<>();
        doAnswer(inv -> {
            written.add(inv.getArgument(0));
            return null;
        }).when(manager).writeFact(any());
        when(manager.queryFacts(any(), any(), any(), any())).thenReturn(List.of());

        DefaultMemoryService withMgr = new DefaultMemoryService();
        ReflectionTestUtils.setField(withMgr, "memoryStore", store);
        ReflectionTestUtils.setField(withMgr, "memoryManager", manager);

        int blobBefore = store.readEntries("user").size();
        Map<String, Object> r1 = withMgr.add("user", "喜欢中文回答");
        Map<String, Object> r2 = withMgr.add("user", "默认 JDK 21");
        assertTrue((Boolean) r1.get("success"));
        assertTrue((Boolean) r2.get("success"));
        assertEquals(blobBefore, store.readEntries("user").size());
        assertEquals(2, written.size());
        assertNotEquals(written.get(0).getSubject(), written.get(1).getSubject());
        assertEquals("喜欢中文回答", written.get(0).getObjectValue());
        assertEquals("默认 JDK 21", written.get(1).getObjectValue());
    }

    @Test
    void 遗留userBlob晋升为事实并清掉blob() {
        MemoryManager manager = mock(MemoryManager.class);
        List<SemanticFact> stored = new ArrayList<>();
        doAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return null;
        }).when(manager).writeFact(any());
        when(manager.queryFacts(any(), any(), any(), any())).thenAnswer(inv -> List.copyOf(stored));

        DefaultMemoryService withMgr = new DefaultMemoryService();
        ReflectionTestUtils.setField(withMgr, "memoryStore", store);
        ReflectionTestUtils.setField(withMgr, "memoryManager", manager);

        withMgr.promoteUserBlob();
        assertTrue(store.readEntries("user").isEmpty());
        assertEquals(1, stored.size());
        assertEquals("沟通偏好：先结论后细节", stored.get(0).getObjectValue());

        withMgr.promoteUserBlob();
        assertEquals(1, stored.size());
        verify(manager, times(1)).writeFact(any());
    }
}
