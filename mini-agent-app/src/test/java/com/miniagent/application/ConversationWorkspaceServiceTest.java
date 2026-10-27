package com.miniagent.application;

import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.storage.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话工作目录的校验规则。
 *
 * <p>这些规则是安全边界：一条过宽的放行等于把整个磁盘交给模型。所以逐条写成用例，
 * 而不是只靠"调一次接口看看"。</p>
 */
class ConversationWorkspaceServiceTest {

    @TempDir
    Path temp;

    private DatabaseConversationStore store;
    private MediaStorage mediaStorage;
    private ConversationWorkspaceService service;
    private Path defaultRoot;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(DatabaseConversationStore.class);
        mediaStorage = Mockito.mock(MediaStorage.class);
        defaultRoot = temp.resolve("data/workspace");
        Mockito.when(mediaStorage.workspaceDir()).thenReturn(defaultRoot);
        service = new ConversationWorkspaceService(store, mediaStorage);
    }

    @Test
    void acceptsExistingWritableDirectory() throws IOException {
        Path project = Files.createDirectories(temp.resolve("projects/demo"));

        Path validated = service.validate(project.toString());

        assertEquals(project.toRealPath(), validated);
    }

    @Test
    void rejectsBlankInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate("  "));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());
    }

    @Test
    void rejectsRelativePath() {
        // 相对路径的含义取决于进程 cwd，换工作目录后会静默指向别处
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate("projects/demo"));
        assertTrue(e.getMessage().contains("绝对路径"), e.getMessage());
    }

    @Test
    void rejectsMissingDirectory() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate(temp.resolve("nope").toString()));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void rejectsFilePath() throws IOException {
        Path file = Files.createFile(temp.resolve("a.txt"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate(file.toString()));
        assertTrue(e.getMessage().contains("不是目录"), e.getMessage());
    }

    @Test
    void rejectsFilesystemRoot() {
        // 盘符根 / POSIX 根：getParent() 为 null
        Path root = temp.getRoot().getRoot();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate(root.toString()));
        assertTrue(e.getMessage().contains("磁盘根目录"), e.getMessage());
    }

    @Test
    void rejectsOverlongPath() {
        String value = temp.resolve("x".repeat(1100)).toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validate(value));
        assertTrue(e.getMessage().contains("路径过长"), e.getMessage());
    }

    @Test
    void canonicalisesSymbolicLinkToRealPath() throws IOException {
        Path real = Files.createDirectories(temp.resolve("real-project"));
        Path link = temp.resolve("link-project");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 未开启开发者模式时普通用户建不了符号链接，跳过而不是误报失败
            return;
        }
        if (!Files.exists(link)) {
            // 建链接成功但重解析点不可用（部分 Windows/文件系统组合），同样跳过
            return;
        }

        // 存词法路径的话，用户看到的是链接、实际写入的是另一棵树，排查时两边对不上
        assertEquals(real.toRealPath(), service.validate(link.toString()));
    }

    @Test
    void bindPersistsCanonicalPath() throws IOException {
        Path project = Files.createDirectories(temp.resolve("proj"));
        Mockito.when(store.setWorkspaceRoot(7L, "s1", project.toRealPath().toString()))
                .thenReturn(true);

        String stored = service.bind("s1", 7L, project.toString());

        assertEquals(project.toRealPath().toString(), stored);
    }

    @Test
    void bindFailsWhenConversationNotOwned() throws IOException {
        Path project = Files.createDirectories(temp.resolve("proj2"));
        Mockito.when(store.setWorkspaceRoot(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.bind("s1", 7L, project.toString()));
        assertTrue(e.getMessage().contains("不属于当前用户"), e.getMessage());
    }

    @Test
    void sessionRootIsEmptyWhenNotBound() {
        DatabaseConversationStore.Conversation conversation = new DatabaseConversationStore.Conversation();
        conversation.id = "s1";
        conversation.workspaceRoot = null;
        Mockito.when(store.getForUser(7L, "s1")).thenReturn(conversation);

        assertTrue(service.sessionRoot("s1", 7L).isEmpty());
        // 未绑定时必须返回默认工作区，不能返回空 —— 端点要靠它找文件
        assertEquals(defaultRoot.toAbsolutePath().normalize(), service.effectiveRoot("s1", 7L));
    }

    @Test
    void sessionRootFallsBackWhenBoundDirectoryDisappeared() throws IOException {
        Path project = Files.createDirectories(temp.resolve("gone"));
        DatabaseConversationStore.Conversation conversation = new DatabaseConversationStore.Conversation();
        conversation.id = "s1";
        conversation.workspaceRoot = project.toString();
        Mockito.when(store.getForUser(7L, "s1")).thenReturn(conversation);

        assertTrue(service.sessionRoot("s1", 7L).isPresent());
        Files.delete(project);

        // 目录被删之后不能让整个任务失败，退回默认工作区并保留 WARN
        assertTrue(service.sessionRoot("s1", 7L).isEmpty());
        assertEquals(defaultRoot.toAbsolutePath().normalize(), service.effectiveRoot("s1", 7L));
    }

    @Test
    void sessionRootReturnsBoundDirectory() throws IOException {
        Path project = Files.createDirectories(temp.resolve("bound"));
        DatabaseConversationStore.Conversation conversation = new DatabaseConversationStore.Conversation();
        conversation.workspaceRoot = project.toString();
        Mockito.when(store.getForUser(7L, "s1")).thenReturn(conversation);

        Optional<Path> root = service.sessionRoot("s1", 7L);

        assertTrue(root.isPresent());
        assertEquals(project.toAbsolutePath().normalize(), root.get());
    }

    @Test
    void unbindClearsBinding() {
        Mockito.when(store.setWorkspaceRoot(7L, "s1", null)).thenReturn(true);

        service.unbind("s1", 7L);

        Mockito.verify(store).setWorkspaceRoot(7L, "s1", null);
        assertFalse(false);
    }
}
