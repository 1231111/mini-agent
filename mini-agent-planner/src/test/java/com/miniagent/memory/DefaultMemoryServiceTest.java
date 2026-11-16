package com.miniagent.memory;

import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.MemoryReadPolicy;
import com.miniagent.memory.model.SemanticFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultMemoryServiceTest {

    @TempDir
    Path tempDir;

    private DefaultMemoryService service;
    private MemoryManager manager;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("tenant-a");
        store = new MemoryStore(tempDir);
        store.loadFromDisk();
        manager = mock(MemoryManager.class);
        service = new DefaultMemoryService();
        ReflectionTestUtils.setField(service, "memoryStore", store);
        ReflectionTestUtils.setField(service, "memoryManager", manager);
    }

    @AfterEach
    void clearOwner() {
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void savedPreferenceReturnsOwnerScopedReadbackReceipt() {
        SemanticFact saved = fact(31L, "请使用中文回答", "请使用中文回答");
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of(saved));

        Map<String, Object> result = service.add("user", "请使用中文回答");

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("persisted"));
        assertEquals(31L, result.get("fact_id"));
        assertEquals("请使用中文回答", result.get("readback"));
        assertTrue(store.readEntries("user").isEmpty());
        ArgumentCaptor<SemanticFact> written = ArgumentCaptor.forClass(SemanticFact.class);
        verify(manager).writeFact(written.capture());
        assertEquals(MemoryScope.ofUser("tenant-a", "7"), written.getValue().getScope());
    }

    @Test
    void writeWithoutReadbackMustNotClaimPersisted() {
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.add("user", "请使用中文回答"));
    }

    @Test
    void userReadStorageFailureIsNotAnEmptySuccess() {
        when(manager.queryFacts("tenant-a", "USER", "7", null))
                .thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> service.read("user"));
    }

    @Test
    void replaceKeepsFactIdentityAndVerifiesOldValueGone() {
        SemanticFact old = fact(11L, "回答语言", "请使用英文回答");
        SemanticFact saved = fact(12L, "回答语言", "请使用中文回答");
        when(manager.queryFacts("tenant-a", "USER", "7", null))
                .thenReturn(List.of(old), List.of(saved), List.of(saved));

        Map<String, Object> result = service.replace("user", "英文", "请使用中文回答");

        assertEquals(true, result.get("persisted"));
        assertEquals(12L, result.get("fact_id"));
        ArgumentCaptor<SemanticFact> written = ArgumentCaptor.forClass(SemanticFact.class);
        verify(manager).writeFact(written.capture());
        assertEquals("回答语言", written.getValue().getSubject());
        assertEquals("请使用中文回答", written.getValue().getObjectValue());
    }

    @Test
    void replaceWithNoMatchOrAmbiguousMatchDoesNotAppendPreference() {
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(
                List.of(), List.of(fact(1L, "语言", "喜欢中文"), fact(2L, "格式", "喜欢列表")));
        assertEquals(false, service.replace("user", "英文", "喜欢中文").get("success"));
        assertEquals(false, service.replace("user", "喜欢", "喜欢中文").get("success"));
        verify(manager, never()).writeFact(any());
    }

    @Test
    void removeDeletesStructuredPreferenceUsingCurrentOwnerAndChecksReadback() {
        SemanticFact saved = fact(18L, "回答格式", "请使用列表回答");
        when(manager.queryFacts("tenant-a", "USER", "7", null))
                .thenReturn(List.of(saved), List.of());
        when(manager.removeFact("tenant-a", "USER", "7", 18L)).thenReturn(true);

        Map<String, Object> result = service.remove("user", "列表");

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("persisted"));
        verify(manager).removeFact("tenant-a", "USER", "7", 18L);
    }

    @Test
    void removeMustNotClaimSuccessIfPreferenceIsStillReadable() {
        SemanticFact saved = fact(18L, "回答格式", "请使用列表回答");
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of(saved));
        when(manager.removeFact("tenant-a", "USER", "7", 18L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.remove("user", "列表"));
    }

    @Test
    void readSortsActivePreferencesFromOldestToNewest() {
        SemanticFact old = fact(1L, "旧偏好", "请简洁回答");
        old.setCreatedAt(1000L);
        SemanticFact recent = fact(2L, "新偏好", "请提供详细例子");
        recent.setCreatedAt(2000L);
        SemanticFact removed = fact(3L, "已删", "不要保留");
        removed.setValidTo(3000L);
        SemanticFact learned = fact(4L, "任务", "上次任务已完成");
        learned.setPredicate(MemoryKeys.PREDICATE_LEARNED);
        when(manager.queryFacts("tenant-a", "USER", "7", null))
                .thenReturn(List.of(recent, removed, learned, old));
        assertEquals(List.of("请简洁回答", "请提供详细例子"), service.read("user"));
    }

    @Test
    void repeatedPreferenceKeepsItsMostRecentSavePosition() {
        SemanticFact old = fact(1L, "回答风格", "请简洁回答");
        old.setCreatedAt(1000L);
        SemanticFact middle = fact(2L, "举例偏好", "请提供详细例子");
        middle.setCreatedAt(2000L);
        SemanticFact recent = fact(3L, "再次确认风格", "请简洁回答");
        recent.setCreatedAt(3000L);
        when(manager.queryFacts("tenant-a", "USER", "7", null))
                .thenReturn(List.of(recent, old, middle));

        assertEquals(List.of("请提供详细例子", "请简洁回答"), service.read("user"));
    }

    @Test
    void learnedFactsAvoidDuplicateNotesWithoutBecomingUserPreferences() {
        SemanticFact learned = fact(31L, "工具约定", "浏览器提取必须保留页面来源");
        learned.setPredicate(MemoryKeys.PREDICATE_LEARNED);
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of(learned));

        Map<String, Object> result = service.add("memory", learned.getObjectValue());

        assertEquals(true, result.get("success"));
        assertEquals("已在结构化记忆中，未写入非结构化笔记。", result.get("message"));
        assertTrue(service.read("user").isEmpty());
    }

    @Test
    void promptOnlyInjectsPreferencesRelevantToCurrentQuery() {
        SemanticFact movie = fact(41L, "内容偏好", "喜欢科幻电影和电影资讯");
        SemanticFact music = fact(42L, "内容偏好", "喜欢古典音乐和音乐资讯");
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of(movie, music));
        when(manager.buildContext(any())).thenReturn(new com.miniagent.memory.model.MemoryContext());

        String prompt = service.retrieveForPrompt("session-1", "搜索今日电影新闻",
                new MemoryReadPolicy(false, false, true, 0));

        assertTrue(prompt.contains("科幻电影"));
        assertFalse(prompt.contains("古典音乐"));
    }

    @Test
    void emptyOrUnrelatedQueryDoesNotInjectAllPreferences() {
        SemanticFact preference = fact(43L, "内容偏好", "喜欢古典音乐");
        when(manager.queryFacts("tenant-a", "USER", "7", null)).thenReturn(List.of(preference));
        when(manager.buildContext(any())).thenReturn(new com.miniagent.memory.model.MemoryContext());

        assertEquals("", service.retrieveForPrompt("session-1", "",
                new MemoryReadPolicy(false, false, true, 0)));
        assertEquals("", service.retrieveForPrompt("session-1", "搜索今日电影新闻",
                new MemoryReadPolicy(false, false, true, 0)));
    }

    private static SemanticFact fact(Long id, String subject, String value) {
        SemanticFact fact = new SemanticFact();
        fact.setId(id);
        fact.setTenantId("tenant-a");
        fact.setScope(MemoryScope.ofUser("tenant-a", "7"));
        fact.setSubject(subject);
        fact.setPredicate(MemoryKeys.PREDICATE_PREFERENCE);
        fact.setObjectValue(value);
        return fact;
    }

    @Test
    void 结构化已含的blob行被丢掉() {
        String structured = "## 记忆\n用户偏好:\n- 回复使用中文，默认 JDK 21\n";
        String blob = "════\n相关记忆\n════\n回复使用中文，默认 JDK 21\n环境用 Windows\n";
        String kept = DefaultMemoryService.dropOverlappingBlobLines(blob, structured);
        assertFalse(kept.contains("JDK 21"));
        assertTrue(kept.contains("环境用 Windows"));
    }

    @Test
    void 无结构化时blob原样返回() {
        String blob = "一条非结构化笔记";
        assertEquals(blob, DefaultMemoryService.dropOverlappingBlobLines(blob, ""));
    }
}
