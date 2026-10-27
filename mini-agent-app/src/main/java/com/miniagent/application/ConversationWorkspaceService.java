package com.miniagent.application;

import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话级工作目录。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>在此之前工作目录是应用级的：{@link MediaStorage#workspaceDir()} 固定返回
 * {@code {dataDir}/workspace}，所有会话共用。于是"让 agent 在某个真实项目里干活"这件事
 * 做不成 —— 它只能把文件写到数据目录下，再由人去告诉模型目标项目在哪，而
 * {@code read_file} 之类的读取又被 {@code PathGuard} 拦在允许的根之外。</p>
 *
 * <h2>安全边界（这一层的全部职责）</h2>
 *
 * <p>校验只放在服务端，不依赖前端下拉框 —— 前端能把任何字符串发上来。规则：</p>
 * <ol>
 *   <li><b>必须是绝对路径</b>：相对路径的含义取决于进程 cwd，重装或换目录后会静默指到别处。</li>
 *   <li><b>必须存在且是目录</b>：不替用户创建目录。用户输错一个字母就凭空建一棵树，
 *       比直接报错更难发现。</li>
 *   <li><b>必须可写</b>：只读目录会让任务跑到一半在 {@code write_file} 上失败，
 *       而失败信息看起来像工具坏了。</li>
 *   <li><b>落盘前取 {@code toRealPath()}</b>：把符号链接/junction 折成真实路径再存。
 *       存词法路径的话，用户看到的是链接路径、实际写入的是另一棵树，
 *       排查时两边对不上。</li>
 *   <li><b>拒绝整盘与系统目录</b>（见 {@link #forbiddenRoot}）：把工作目录设成
 *       {@code C:\} 或 {@code C:\Windows} 等于把 {@code PathGuard} 的根换成整个系统盘，
 *       模型一次提示注入就能改系统文件。这是唯一一处"用户想做也不让做"的地方，
 *       拒绝消息里说明原因和替代做法。</li>
 * </ol>
 *
 * <p>不在这里校验归属：{@code userId} 由调用方从 JWT 取，交给
 * {@link DatabaseConversationStore#setWorkspaceRoot(Long, String, String)} 做
 * "id + userId + 未删除"三条件更新，越权自然落到 0 行受影响。</p>
 */
@Slf4j
@Service
public class ConversationWorkspaceService {

    /** 与 {@code chat_conversations.workspace_root} 的列宽一致。 */
    private static final int MAX_PATH_LENGTH = 1000;

    private final DatabaseConversationStore conversationStore;
    private final MediaStorage mediaStorage;

    public ConversationWorkspaceService(DatabaseConversationStore conversationStore,
                                        MediaStorage mediaStorage) {
        this.conversationStore = conversationStore;
        this.mediaStorage = mediaStorage;
    }

    /** 会话未绑定目录时使用的工作区。 */
    public Path defaultRoot() {
        return mediaStorage.workspaceDir().toAbsolutePath().normalize();
    }

    /**
     * 这次运行要用的工作目录。
     *
     * <p><b>返回 empty 与"返回默认目录"是两件事</b>：empty 表示"该会话没绑定目录，
     * 调用方不要覆盖线程上的工作区"（默认工作区可能已经被子 Agent 覆盖成隔离目录）；
     * 绑定目录失效时返回空并打 WARN，让任务退回默认工作区继续，而不是整个失败。</p>
     */
    public Optional<Path> sessionRoot(String sessionId, Long userId) {
        if (StringUtils.isBlank(sessionId) || Objects.isNull(userId)) {
            return Optional.empty();
        }
        DatabaseConversationStore.Conversation conversation =
                conversationStore.getForUser(userId, sessionId.trim());
        if (conversation == null || StringUtils.isBlank(conversation.workspaceRoot)) {
            return Optional.empty();
        }
        String stored = conversation.workspaceRoot.trim();
        try {
            Path root = Path.of(stored).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                log.warn("会话绑定的工作目录已不存在，退回默认工作区: session={}, path={}", sessionId, stored);
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (InvalidPathException e) {
            log.warn("会话绑定的工作目录无法解析，退回默认工作区: session={}, path={}", sessionId, stored);
            return Optional.empty();
        }
    }

    /** 当前生效的目录：绑定了就用绑定的，否则是默认工作区。 */
    public Path effectiveRoot(String sessionId, Long userId) {
        return sessionRoot(sessionId, userId).orElseGet(this::defaultRoot);
    }

    /**
     * 校验并绑定工作目录。返回落库的规范路径。
     *
     * @throws IllegalArgumentException 校验不通过，消息可直接展示给用户
     */
    public String bind(String sessionId, Long userId, String rawPath) {
        Path canonical = validate(rawPath);
        if (!conversationStore.setWorkspaceRoot(userId, sessionId, canonical.toString())) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户");
        }
        log.info("会话工作目录已绑定: session={}, path={}", sessionId, canonical);
        return canonical.toString();
    }

    /** 恢复默认工作区。 */
    public void unbind(String sessionId, Long userId) {
        if (!conversationStore.setWorkspaceRoot(userId, sessionId, null)) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户");
        }
        log.info("会话工作目录已恢复默认: session={}", sessionId);
    }

    /**
     * 路径校验（不含归属判定）。抽成独立方法是为了能直接测：
     * 这些规则是安全边界，不该只有"跑一遍接口看看"这一条验证路径。
     */
    Path validate(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw new IllegalArgumentException("工作目录不能为空");
        }
        String value = rawPath.trim();
        if (value.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("路径过长（上限 " + MAX_PATH_LENGTH + " 字符）");
        }
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径格式不合法：" + value);
        }
        if (!candidate.isAbsolute()) {
            throw new IllegalArgumentException("请填写绝对路径，例如 D:/projects/my-app");
        }
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("目录不存在：" + value);
        }
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("这不是目录：" + value);
        }
        Path canonical;
        try {
            canonical = candidate.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("目录无法访问：" + value + "（" + e.getMessage() + "）");
        }
        String forbidden = forbiddenRoot(canonical);
        if (forbidden != null) {
            throw new IllegalArgumentException(forbidden);
        }
        if (!Files.isWritable(canonical)) {
            throw new IllegalArgumentException("目录不可写：" + canonical);
        }
        return canonical;
    }

    /**
     * 拒绝"把整个系统盘当选区"。
     *
     * <p>只有这几类：文件系统根（{@code C:\}、{@code /}）、Windows 系统目录、
     * Program Files。用户主目录<b>不拦</b> —— 那是真实存在的开发场景
     * （{@code ~/projects}），拦掉会让功能形同虚设；风险由 UI 上的提示承担。</p>
     */
    private static String forbiddenRoot(Path canonical) {
        if (canonical.getParent() == null) {
            return "不能把整个磁盘根目录作为工作目录，请选择具体项目目录。";
        }
        for (String name : new String[]{"WINDIR", "SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String configured = System.getenv(name);
            if (StringUtils.isBlank(configured)) {
                continue;
            }
            try {
                Path system = Path.of(configured).toRealPath();
                if (canonical.equals(system)
                        || canonical.startsWith(system)) {
                    return "不能把系统目录作为工作目录：" + canonical;
                }
            } catch (IOException | InvalidPathException ignored) {
                // 该环境变量指向的目录不存在（Linux 上没有这些键），忽略
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && canonical.getNameCount() == 1
                && canonical.toString().toLowerCase(Locale.ROOT).contains("windows")) {
            return "不能把系统目录作为工作目录：" + canonical;
        }
        return null;
    }
}
