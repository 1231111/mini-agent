package com.miniagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产级记忆系统回归：隔离、快照、上限、向量降级。
 */
class MemoryStoreProductionTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir);
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
    }

    @AfterEach
    void tearDown() {
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
    }

    @Test
    void 用户隔离_互不可见() {
        MemoryStore.setCurrentUser(1L);
        store.add("memory", "用户1的私有笔记");

        MemoryStore.setCurrentUser(2L);
        store.add("memory", "用户2的私有笔记");

        MemoryStore.setCurrentUser(1L);
        assertTrue(store.readEntries("memory").stream().anyMatch(s -> s.contains("用户1")));
        assertFalse(store.readEntries("memory").stream().anyMatch(s -> s.contains("用户2")));

        MemoryStore.setCurrentUser(2L);
        assertTrue(store.readEntries("memory").stream().anyMatch(s -> s.contains("用户2")));
        assertFalse(store.readEntries("memory").stream().anyMatch(s -> s.contains("用户1")));
    }

    @Test
    void 同会话内add后快照不刷新_注入仍读旧内容() {
        MemoryStore.setCurrentUser(100L);
        store.loadFromDisk();
        String emptySnapshot = store.getMemorySnapshot();
        assertEquals("", emptySnapshot, "会话初快照应为空");

        store.add("memory", "第一轮已知事实");
        assertTrue(store.readEntries("memory").stream().anyMatch(s -> s.contains("第一轮")));
        assertFalse(store.getMemorySnapshot().contains("第一轮"),
                "BUG: add 落盘后 memorySnapshot 未重建，readEntries 与注入快照不一致");

        Map<String, Object> addResult = store.add("memory", "第二轮新增事实");
        assertTrue((Boolean) addResult.get("success"));
        assertEquals(2, store.readEntries("memory").size());

        String snapshotAfterAdd = store.getMemorySnapshot();
        assertFalse(snapshotAfterAdd.contains("第一轮已知事实"));
        assertFalse(snapshotAfterAdd.contains("第二轮新增事实"),
                "BUG: 同会话多次 add 后注入快照仍为空/旧值");

        String querySnapshot = store.getSnapshotForQuery("第二轮");
        assertFalse(querySnapshot.contains("第二轮新增事实"),
                "无向量时 getSnapshotForQuery 走冻结快照，同轮写入对 prompt 不可见");
    }

    @Test
    void loadFromDisk_会刷新快照() {
        MemoryStore.setCurrentUser(100L);
        store.add("memory", "待刷新条目");
        store.loadFromDisk();
        assertTrue(store.getMemorySnapshot().contains("待刷新条目"));
    }

    @Test
    void 字符上限_拒绝超限追加() {
        MemoryStore.setCurrentUser(100L);
        String chunk = "A".repeat(500);
        for (int i = 0; i < 4; i++) {
            Map<String, Object> r = store.add("memory", chunk + i);
            assertTrue((Boolean) r.get("success"), "第 " + i + " 条应成功");
        }
        Map<String, Object> overflow = store.add("memory", "X".repeat(300));
        assertFalse((Boolean) overflow.get("success"));
        assertTrue(overflow.get("message").toString().contains("字符"));
    }

    @Test
    void 重复条目_幂等不膨胀() {
        MemoryStore.setCurrentUser(100L);
        store.add("memory", "固定偏好：中文回答");
        store.add("memory", "固定偏好：中文回答");
        assertEquals(1, store.readEntries("memory").size());
    }

    @Test
    void 向量召回为空时_回退全量快照() {
        MemoryStore.setCurrentUser(100L);
        store.add("memory", "向量库尚未建立时的笔记");
        store.loadFromDisk();

        FakeVectorIndex vec = new FakeVectorIndex(true);
        store.setVectorStore(vec);

        String snap = store.getSnapshotForQuery("无关查询", true, false, false, 0);
        assertTrue(snap.contains("向量库尚未建立时的笔记"),
                "recall 空集时应回退 memorySnapshot 全量注入");
    }

    @Test
    void 向量召回非空时_只注入召回子集() {
        MemoryStore.setCurrentUser(100L);
        store.add("memory", "Alpha 项目约定");
        store.add("memory", "Beta 部署流程");
        store.loadFromDisk();

        FakeVectorIndex vec = new FakeVectorIndex(true);
        vec.nextRecall = List.of("Beta 部署流程");
        store.setVectorStore(vec);

        String snap = store.getSnapshotForQuery("部署", true, false, false, 0);
        assertTrue(snap.contains("Beta 部署流程"));
        assertFalse(snap.contains("Alpha 项目约定"),
                "向量命中子集时不应再注入未召回条目");
    }

    @Test
    void 未绑定用户_落到_default桶() {
        Map<String, Object> r = store.add("memory", "匿名用户笔记");
        assertTrue((Boolean) r.get("success"));
        assertTrue(tempDir.resolve("users/_default/MEMORY.md").toFile().exists()
                || store.readEntries("memory").size() == 1);
    }

    /** 可控的假向量索引，避免测试依赖 embedding API。 */
    static final class FakeVectorIndex implements MemoryVectorIndex {
        private final boolean enabled;
        List<String> nextRecall = List.of();

        FakeVectorIndex(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean hasIndex(Long userId) {
            return true;
        }

        @Override
        public void reindex(Long userId, List<String> entries) {
            // no-op
        }

        @Override
        public List<String> recall(Long userId, String query) {
            return new ArrayList<>(nextRecall);
        }
    }
}
