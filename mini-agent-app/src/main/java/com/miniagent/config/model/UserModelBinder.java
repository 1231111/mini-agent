package com.miniagent.config.model;

import com.miniagent.common.context.OwnerContext;

import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 把「某个用户自己配置的模型」和「归属上下文」绑定到当前线程。
 *
 * <p>为什么需要它：一次请求真正该用的是用户配置的那套模型（{@code user_model_config}
 * 覆盖 {@code agent.models.presets} 后回退全局）。交互入口在
 * {@code AgentChatApplicationService} 里按 userId 解析并安装到线程；但 {@code @Scheduled}
 * 与 {@code CompletableFuture} 跑在**新线程**上，ThreadLocal 不继承，于是辅助 LLM 调用
 * 会静默回退到全局 {@code @Primary} Bean。
 *
 * <p>后果很隐蔽：全局配置里的 key 失效、而用户配置的 key 可用时，表现为主对话一切正常、
 * 后台任务（记忆巩固、每日画像）稳定 401。
 *
 * <p>三档绑定可单独使用，也可用 {@link #bindUser} 一次绑好：
 * <ul>
 *   <li>{@link #bindModel} —— 只绑模型，用于不涉及用户目录读写的场景</li>
 *   <li>{@link #bindOwner} —— 只绑归属，保证记忆文件落到对应用户目录</li>
 *   <li>{@link #bindUser} —— 模型 + 归属（租户自动从 {@code users} 表解析）</li>
 * </ul>
 *
 * <p>所有绑定都用 try-with-resources 保证退出时精确恢复，支持嵌套。没有用户时可返回空绑定；
 * 已知用户的模型解析失败必须中止调用，不能将其会话内容改发给全局模型。
 */
@Slf4j
@Component
public class UserModelBinder {

    @Autowired
    private ModelClientFactory modelClientFactory;

    @Autowired
    private DatabaseConversationStore conversationStore;

    @Autowired(required = false)
    private UserRepository userRepository;

    /** 会话归属的用户（{@code chat_conversations.id} 即 sessionId）。查不到返回空。 */
    public Optional<Long> userIdOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        // A lookup failure is not evidence that the session has no owner.
        // Propagate it so background callers cannot silently use platform models.
        return conversationStore.findUserIdBySession(sessionId);
    }

    /** 按 sessionId 反查归属用户后一次绑好模型与归属；无归属用户时返回空绑定。 */
    public Binding bindForSession(String sessionId) {
        Optional<Long> userId = userIdOfSession(sessionId);
        if (userId.isEmpty()) {
            log.debug("会话未登记归属，无法绑定用户模型: session={}", sessionId);
            return Binding.noop();
        }
        return bindUser(userId.get());
    }

    /** 模型 + 归属一次绑好；租户从 {@code users} 表解析。 */
    public Binding bindUser(Long userId) {
        if (userId == null) {
            return Binding.noop();
        }
        Binding owner = bindOwner(userId, resolveTenantId(userId));
        try {
            Binding model = bindModel(userId);
            return Binding.of(model, owner);
        } catch (RuntimeException | Error failed) {
            owner.close();
            throw failed;
        }
    }

    /** 只绑该用户的模型。配置无效时抛出异常，由后台任务保留未处理状态并提示修复。 */
    public Binding bindModel(Long userId) {
        if (userId == null) {
            return Binding.noop();
        }
        ModelClientFactory.ResolvedModels models = modelClientFactory.resolve(userId);
        log.debug("已绑定用户模型: userId={}, model={}", userId, models.settings().modelName());
        // 窗口必须一起绑：后台线程（记忆巩固、每日画像、压缩摘要）用的是同一条绑定量，
        // 不传窗口时这些线程按「窗口未知」处理，压缩判据退化到配置上限。
        return Binding.wrap(EffectiveModelContext.bind(models.chat(), models.streaming(),
                models.settings().contextWindowTokens()));
    }

    /** 只绑归属（记忆目录按 userId 隔离，这一步不能省）。 */
    public Binding bindOwner(Long userId, String tenantId) {
        if (userId == null) {
            return Binding.noop();
        }
        return Binding.wrap(
                OwnerContext.bind(new OwnerContext(userId, tenantId)));
    }

    private String resolveTenantId(Long userId) {
        if (userRepository == null) {
            return null;
        }
        try {
            return userRepository.findById(userId)
                    .map(User::getTenantId)
                    .map(String::valueOf)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("租户解析失败: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 绑定句柄。先绑定 owner 再绑定 model，关闭时反向恢复（先 model 后 owner）。
     */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {

        @Override
        void close();

        static Binding noop() {
            return () -> { };
        }

        /** 包住一个不抛 checked exception 的关闭动作（如 {@code EffectiveModelContext.bind} 的返回值）。 */
        static Binding wrap(AutoCloseable closeable) {
            if (closeable == null) {
                return noop();
            }
            return () -> {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 恢复上下文失败不应打断调用方
                }
            };
        }

        static Binding of(Binding modelBinding, Binding ownerBinding) {
            Binding model = modelBinding == null ? noop() : modelBinding;
            Binding owner = ownerBinding == null ? noop() : ownerBinding;
            return () -> {
                try {
                    model.close();
                } finally {
                    owner.close();
                }
            };
        }
    }
}
