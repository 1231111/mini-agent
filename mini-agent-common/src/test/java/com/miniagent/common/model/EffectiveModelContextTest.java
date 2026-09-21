package com.miniagent.common.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link EffectiveModelContext} 的绑定语义。
 *
 * <p>它承载的是全项目最关键的一条不变量：辅助 LLM 调用（压缩摘要、todo 验收、目标编译、
 * 子代理、图片质检、记忆巩固/画像）必须跟随「用户配置的模型」，全局 Bean 只是兜底。
 * 一旦这里退化，症状就是主对话正常、后台任务因为全局 key 失效而稳定 401 —— 很难从日志看出来。
 */
class EffectiveModelContextTest {

    private final ChatModel global = mock(ChatModel.class);
    private final ChatModel userModel = mock(ChatModel.class);
    private final StreamingChatModel userStreaming = mock(StreamingChatModel.class);

    @AfterEach
    void tearDown() {
        EffectiveModelContext.clear();
    }

    @Test
    void fallsBackToGlobalWhenNothingBound() {
        assertFalse(EffectiveModelContext.isBound());
        assertNull(EffectiveModelContext.currentChat());
        assertSame(global, EffectiveModelContext.chatOr(global));
    }

    @Test
    void boundModelWinsOverFallback() {
        EffectiveModelContext.set(userModel, userStreaming);
        assertTrue(EffectiveModelContext.isBound());
        assertSame(userModel, EffectiveModelContext.currentChat());
        assertSame(userStreaming, EffectiveModelContext.currentStreaming());
        assertSame(userModel, EffectiveModelContext.chatOr(global));
    }

    @Test
    void setWithNullClearsOnlyThatSlot() {
        EffectiveModelContext.set(userModel, userStreaming);
        EffectiveModelContext.set(null, userStreaming);
        assertFalse(EffectiveModelContext.isBound());
        assertNull(EffectiveModelContext.currentChat());
        assertSame(userStreaming, EffectiveModelContext.currentStreaming());
    }

    @Test
    void clearResetsBothSlots() {
        EffectiveModelContext.set(userModel, userStreaming);
        EffectiveModelContext.clear();
        assertNull(EffectiveModelContext.currentChat());
        assertNull(EffectiveModelContext.currentStreaming());
        assertSame(global, EffectiveModelContext.chatOr(global));
    }

    @Test
    void bindRestoresPreviousSnapshotOnClose() {
        EffectiveModelContext.set(global, null);
        try (var ignored = EffectiveModelContext.bind(userModel, userStreaming)) {
            assertSame(userModel, EffectiveModelContext.currentChat());
            assertSame(userStreaming, EffectiveModelContext.currentStreaming());
        }
        // 恢复进入前的快照，而不是清空
        assertSame(global, EffectiveModelContext.currentChat());
        assertNull(EffectiveModelContext.currentStreaming());
    }

    @Test
    void nestedBindRestoresEachLevel() {
        ChatModel inner = mock(ChatModel.class);
        try (var outer = EffectiveModelContext.bind(userModel, null)) {
            assertSame(userModel, EffectiveModelContext.currentChat());
            try (var innerScope = EffectiveModelContext.bind(inner, null)) {
                assertSame(inner, EffectiveModelContext.currentChat());
            }
            assertSame(userModel, EffectiveModelContext.currentChat());
            assertNotSame(inner, EffectiveModelContext.currentChat());
        }
        assertFalse(EffectiveModelContext.isBound());
    }

    @Test
    void bindNullIsSafeAndNoop() {
        try (var ignored = EffectiveModelContext.bind(null, null)) {
            assertNull(EffectiveModelContext.currentChat());
        }
        assertNull(EffectiveModelContext.currentChat());
    }

    /**
     * ThreadLocal 不跨线程继承 —— 这正是「主对话正常、后台任务用了另一把 key」的根源。
     * 后台任务（{@code @Scheduled}、{@code CompletableFuture}）必须自己 bind。
     */
    @Test
    void bindingIsNotInheritedByOtherThreads() throws Exception {
        EffectiveModelContext.set(userModel, null);
        AtomicReference<ChatModel> rawInChild = new AtomicReference<>();
        AtomicReference<ChatModel> resolvedInChild = new AtomicReference<>();
        Thread child = new Thread(() -> {
            rawInChild.set(EffectiveModelContext.currentChat());
            resolvedInChild.set(EffectiveModelContext.chatOr(global));
        });
        child.start();
        child.join();

        assertNull(rawInChild.get(), "子线程不应继承父线程的绑定");
        assertSame(global, resolvedInChild.get(), "子线程未绑定时应回退全局 Bean");
    }
}
