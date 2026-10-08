package com.miniagent.common.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 当前运行生效的对话模型（ThreadLocal 持有）。
 *
 * <p>存在的理由：一次请求真正该用的是「用户自己配的那套模型」——由 {@code user_model_config}
 * 覆盖 {@code agent.models.presets}、再回退到 {@code langchain4j.open-ai.chat-model} 解析得到。
 * 而 {@code langchain4j.open-ai.chat-model} 这个全局 {@code @Primary} Bean 只是最后一档兜底。
 *
 * <p>任何辅助 LLM 调用（上下文压缩摘要、todo 语义验收、目标编译、子代理、
 * 图片质检、记忆巩固、每日画像、重要度评估）若直接注入那个全局 Bean，就等于绕开用户配置。
 * 一旦全局配置里的 key 失效而用户配置的 key 可用，症状就是「主对话正常、后台任务 401」。
 *
 * <p>因此辅助调用一律经 {@link #chatOr(ChatModel)} 取当前模型，全局 Bean 仅作为未绑定时的兜底。
 *
 * <h3>线程语义（必须遵守）</h3>
 * ThreadLocal 不跨线程继承。{@code CompletableFuture}、线程池、{@code @Scheduled} 等异步执行
 * 必须在新线程内显式 {@link #bind(ChatModel, StreamingChatModel)}，否则读到 null 并静默回退到
 * 全局 Bean —— 这正是上面那类错配最隐蔽的来源。
 */
public final class EffectiveModelContext {

    private static final ThreadLocal<ChatModel> CURRENT_CHAT = new ThreadLocal<>();
    private static final ThreadLocal<StreamingChatModel> CURRENT_STREAMING = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_WINDOW = new ThreadLocal<>();

    private EffectiveModelContext() {}

    /** 安装当前模型；某一项传 null 表示清除该项（与另一项无关）。 */
    public static void set(ChatModel chat, StreamingChatModel streaming) {
        set(chat, streaming, 0);
    }

    /**
     * 安装当前模型及其**厂商侧上下文窗口**（token，0 = 未知）。
     *
     * <p>窗口必须跟着模型一起走：循环用它决定何时压缩。写死一个 512k 的工作窗口，
     * 遇到 32k/128k 的模型就会"永远不压缩 → 上游 context-length 400 → 被误报成网络故障"。</p>
     */
    public static void set(ChatModel chat, StreamingChatModel streaming, int contextWindowTokens) {
        if (chat == null) {
            CURRENT_CHAT.remove();
        } else {
            CURRENT_CHAT.set(chat);
        }
        if (streaming == null) {
            CURRENT_STREAMING.remove();
        } else {
            CURRENT_STREAMING.set(streaming);
        }
        if (contextWindowTokens > 0) {
            CURRENT_WINDOW.set(contextWindowTokens);
        } else {
            CURRENT_WINDOW.remove();
        }
    }

    public static void clear() {
        CURRENT_CHAT.remove();
        CURRENT_STREAMING.remove();
        CURRENT_WINDOW.remove();
    }

    /** 当前模型的上下文窗口（token）；未声明返回 0，调用方回退到配置的工作窗口。 */
    public static int currentContextWindow() {
        Integer w = CURRENT_WINDOW.get();
        return w == null ? 0 : w;
    }

    /** 当前绑定的对话模型，未绑定返回 null（需要兜底请用 {@link #chatOr}）。 */
    public static ChatModel currentChat() {
        return CURRENT_CHAT.get();
    }

    public static StreamingChatModel currentStreaming() {
        return CURRENT_STREAMING.get();
    }

    /** 当前生效的对话模型；未绑定时返回 fallback（通常是全局 {@code @Primary} Bean）。 */
    public static ChatModel chatOr(ChatModel fallback) {
        ChatModel current = CURRENT_CHAT.get();
        return current != null ? current : fallback;
    }

    public static StreamingChatModel streamingOr(StreamingChatModel fallback) {
        StreamingChatModel current = CURRENT_STREAMING.get();
        return current != null ? current : fallback;
    }

    /** 本线程是否已绑定模型。供日志与诊断使用，避免日志里写死 Bean 名。 */
    public static boolean isBound() {
        return CURRENT_CHAT.get() != null;
    }

    /**
     * 作用域绑定：进入时安装，{@link Binding#close()} 时精确恢复进入前的快照（支持嵌套）。
     * 用于后台任务在自身线程内临时绑定某用户的模型。
     */
    public static Binding bind(ChatModel chat, StreamingChatModel streaming) {
        return bind(chat, streaming, 0);
    }

    /** 作用域绑定（含窗口）：进入时安装，关闭时精确恢复进入前的快照。 */
    public static Binding bind(ChatModel chat, StreamingChatModel streaming, int contextWindowTokens) {
        ChatModel previousChat = CURRENT_CHAT.get();
        StreamingChatModel previousStreaming = CURRENT_STREAMING.get();
        Integer previousWindow = CURRENT_WINDOW.get();
        set(chat, streaming, contextWindowTokens);
        return () -> {
            if (previousChat == null) {
                CURRENT_CHAT.remove();
            } else {
                CURRENT_CHAT.set(previousChat);
            }
            if (previousStreaming == null) {
                CURRENT_STREAMING.remove();
            } else {
                CURRENT_STREAMING.set(previousStreaming);
            }
            if (previousWindow == null) {
                CURRENT_WINDOW.remove();
            } else {
                CURRENT_WINDOW.set(previousWindow);
            }
        };
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
