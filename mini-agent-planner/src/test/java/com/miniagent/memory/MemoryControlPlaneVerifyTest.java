package com.miniagent.memory;

import com.miniagent.common.context.OwnerContext;

import com.miniagent.agent.context.ContextLoadPolicy;
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
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("t1");
        store.loadFromDisk();
        store.add("memory", "长期笔记：仓库用 JDK 21");
        try {
            java.nio.file.Files.writeString(tempDir.resolve("users/7/MIDTERM.md"), "中期摘要：上周在做报表项目");
            store.loadFromDisk();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        store.add("user", "沟通偏好：先结论后细节");
    }

    @AfterEach
    void tearDown() {
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    /**
     * 打开记忆通道允许按当前任务检索，不代表将全部笔记和偏好注入。
     * 新任务能召回相关记录，同时排除无关记录和遗留中期摘要。
     */
    @Test
    void 无挂起清单时按当前任务加载相关记忆() {
        MemoryReadPolicy policy = ContextLoadPolicy.defaults().memoryPolicy();
        store.add("memory", "长期笔记：机票报销需要电子行程单");
        String prompt = service.retrieveForPrompt("s1",
                "检查仓库 JDK 21 配置，说明先结论后细节的沟通偏好", policy);
        assertTrue(prompt.contains("JDK 21"), prompt);
        assertTrue(prompt.contains("先结论后细节"), prompt);
        assertFalse(prompt.contains("电子行程单"), prompt);
        assertFalse(prompt.contains("报表项目"), prompt);
    }

    @Test
    void newTask不再注入遗留midterm() {
        MemoryReadPolicy policy = ContextLoadPolicy
                .defaults().memoryPolicy();
        String prompt = service.retrieveForPrompt("s1",
                "使用 JDK 21 编写报表项目方案", policy);
        assertTrue(prompt.contains("JDK 21"), prompt);
        assertFalse(prompt.contains("报表项目"), prompt);
    }

    @Test
    void 无关任务不回退注入全部记忆且不删除已保存记录() {
        String prompt = service.retrieveForPrompt("s1", "搜索今日电影新闻",
                ContextLoadPolicy.defaults().memoryPolicy());

        assertEquals("", prompt);
        assertTrue(store.readEntries("memory").contains("长期笔记：仓库用 JDK 21"));
        assertTrue(store.readEntries("user").contains("沟通偏好：先结论后细节"));
    }

    @Test
    void 空查询和关闭记忆通道均不注入匹配记录() {
        MemoryReadPolicy policy = ContextLoadPolicy.defaults().memoryPolicy();
        assertEquals("", service.retrieveForPrompt("s1", "", policy));
        assertEquals("", service.retrieveForPrompt("s1", "   ", policy));
        assertEquals("", service.retrieveForPrompt("s1",
                "仓库 JDK 21，先结论后细节", MemoryReadPolicy.none()));
    }

    @Test
    void 长期记忆和用户偏好通道分别遵守加载开关() {
        String query = "仓库 JDK 21，先结论后细节";
        String notes = service.retrieveForPrompt("s1", query,
                new MemoryReadPolicy(false, true, false, 0));
        assertTrue(notes.contains("JDK 21"), notes);
        assertFalse(notes.contains("先结论后细节"), notes);

        String preferences = service.retrieveForPrompt("s1", query,
                new MemoryReadPolicy(false, false, true, 0));
        assertTrue(preferences.contains("先结论后细节"), preferences);
        assertFalse(preferences.contains("JDK 21"), preferences);
    }

    @Test
    void 新任务默认打开长期记忆() {
        ContextLoadPolicy p = ContextLoadPolicy.defaults();
        assertTrue(p.injectMemory());
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
        when(manager.queryFacts(any(), any(), any(), any())).thenAnswer(inv -> List.copyOf(written));

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
