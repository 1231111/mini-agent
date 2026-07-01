package com.miniagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 记忆持久化：按层级写入项目根目录下 {@code memory/} 中的 Markdown 文件。
 */
@Slf4j
@Component
public class AgentMemory {

    private static final Path MEMORY_ROOT = Paths.get("memory");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ReentrantLock lock = new ReentrantLock();

    public AgentMemory() {
        try {
            Files.createDirectories(MEMORY_ROOT.resolve(MemoryType.SHORT_TERM.getDirectory()));
            Files.createDirectories(MEMORY_ROOT.resolve(MemoryType.MID_TERM.getDirectory()));
            Files.createDirectories(MEMORY_ROOT.resolve(MemoryType.LONG_TERM.getDirectory()));
        } catch (IOException e) {
            log.error("初始化记忆目录失败", e);
        }
    }

    public void appendShortTerm(String sessionId, MemoryEntry entry) {
        append(MemoryType.SHORT_TERM, "session-" + sanitize(sessionId) + ".md", entry);
    }

    public void appendMidTerm(MemoryEntry entry) {
        append(MemoryType.MID_TERM, "daily-summary-" + LocalDate.now().format(DATE_FMT) + ".md", entry);
    }

    public void appendLongTerm(MemoryEntry entry) {
        append(MemoryType.LONG_TERM, "knowledge-base.md", entry);
    }

    /**
     * 上下文窗口滑动时被逐出的消息，写入长期 spill（Markdown），不依赖 LangChain 类型。
     */
    public void appendSpillFromContextWindow(String sessionId, String messageRole, String text) {
        lock.lock();
        try {
            Path dir = MEMORY_ROOT.resolve(MemoryType.LONG_TERM.getDirectory());
            String file = "context-spill-" + sanitize(sessionId) + ".md";
            Path path = dir.resolve(file);
            String block = "## 移出上下文窗口\n"
                    + "- 时间: " + java.time.LocalDateTime.now() + "\n"
                    + "- 角色: " + messageRole + "\n\n"
                    + text + "\n\n---\n";
            if (!Files.exists(path)) {
                Files.writeString(path,
                        "# 上下文溢出归档（会话 " + sanitize(sessionId) + "）\n\n" + block,
                        StandardOpenOption.CREATE);
            } else {
                Files.writeString(path, "\n" + block, StandardOpenOption.APPEND);
            }
            log.debug("上下文溢出已写入 {}", file);
        } catch (IOException e) {
            log.error("写入上下文 spill 失败", e);
        } finally {
            lock.unlock();
        }
    }

    private void append(MemoryType type, String filename, MemoryEntry entry) {
        lock.lock();
        try {
            Path dir = MEMORY_ROOT.resolve(type.getDirectory());
            Path file = dir.resolve(filename);
            String block = entry.toMarkdown();
            if (!Files.exists(file)) {
                Files.writeString(file,
                        "# " + type.name() + " 记忆\n\n" + block,
                        StandardOpenOption.CREATE);
            } else {
                Files.writeString(file, "\n\n" + block, StandardOpenOption.APPEND);
            }
            log.debug("记忆已写入 {} / {}", type.getDirectory(), filename);
        } catch (IOException e) {
            log.error("写入记忆失败: {}", filename, e);
        } finally {
            lock.unlock();
        }
    }

    private static String sanitize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
