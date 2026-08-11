package com.miniagent.memory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * 持久化记忆存储 — 参考 hermes-agent 的 MemoryStore 设计，按 userId 隔离。
 *
 * 每个用户独立一组文件，位于 memory/users/{userId}/：
 * - MEMORY.md：Agent 个人笔记（环境事实、项目约定、工具怪癖、学到的方法）
 * - USER.md：用户画像（偏好、沟通风格、技术领域、工作习惯）
 * - MIDTERM.md：跨该用户所有会话滚动压缩的中期记忆
 *
 * 设计特性：
 * - 按 userId 隔离：当前用户由 {@link #currentUserId} ThreadLocal 提供（复刻 AgentLoop 的 session 模式），
 *   未设置时落到 _default，多用户互不污染。
 * - § 分隔条目，字符数硬上限（MEMORY 2200，USER 1375，MIDTERM 6000）
 * - 冻结快照：session 开始时加载，session 中写盘但不刷新快照（保持 prefix cache 稳定）
 * - 安全扫描：写入前检测 prompt 注入 / 凭据外泄 / 隐形字符
 * - 文件锁 + 原子重命名：并发安全（每个用户一把锁，覆盖 load 与 增删改）
 * - 单一操作接口：add / replace / remove
 */
public class MemoryStore {

    private static final String ENTRY_DELIMITER = "\n§\n";
    private static final int MEMORY_CHAR_LIMIT = 2200;
    private static final int USER_CHAR_LIMIT = 1375;
    private static final int MIDTERM_CHAR_LIMIT = 6000;
    private static final Long DEFAULT_USER = -1L;

    /** 当前用户 ID（由 AgentChatApplicationService 每轮入口 set/clear）。null → _default。 */
    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    public static void setCurrentUser(Long userId) { currentUserId.set(userId); }
    public static void clearCurrentUser() { currentUserId.remove(); }
    /** 读取当前线程绑定的 userId，供并行工具执行前快照、子线程内重建上下文。 */
    public static Long getCurrentUser() { return currentUserId.get(); }

    /** 每个用户独立的记忆状态。所有可变字段的读写都在该实例的 lock 下进行。 */
    private static final class UserMemory {
        final ReentrantLock lock = new ReentrantLock();
        List<String> memoryEntries = new ArrayList<>();
        List<String> userEntries = new ArrayList<>();
        String midtermMemory = "";
        String memorySnapshot = "";
        String userSnapshot = "";
        String midtermSnapshot = "";
        boolean loaded = false;
    }

    private final Map<Long, UserMemory> users = new ConcurrentHashMap<>();
    private final Path memoryDir;

    /** 可选：长期记忆向量索引。条目变更后同步重建该用户索引。为 null 时不启用向量召回。 */
    private MemoryVectorIndex vectorStore;

    public MemoryStore(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    /** 注入向量索引组件（由配置装配）。 */
    public void setVectorStore(MemoryVectorIndex vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 触发当前用户长期记忆（MEMORY + USER 全部条目）的向量索引重建。 */
    private void reindexVector(Long userId, UserMemory um) {
        if (Objects.isNull(vectorStore) || !vectorStore.isEnabled()) return;
        List<String> all = new ArrayList<>(um.memoryEntries);
        all.addAll(um.userEntries);
        try {
            vectorStore.reindex(userId, all);
        } catch (Exception e) {
            // 向量索引是增强能力，失败不影响主记忆写入。
        }
    }

    // =========================================================================
    // 用户上下文与目录解析
    // =========================================================================

    private Long effectiveUserId() {
        Long uid = currentUserId.get();
        return Optional.ofNullable(uid).orElse(DEFAULT_USER);
    }

    /** 当前用户的记忆根目录：memory/users/{userId}/ （_default 用于匿名/系统）。 */
    private Path userDir(Long userId) {
        String name = DEFAULT_USER.equals(userId) ? "_default" : String.valueOf(userId);
        return memoryDir.resolve("users").resolve(name);
    }

    private Path memoryPath(Long userId) { return userDir(userId).resolve("MEMORY.md"); }
    private Path userPath(Long userId) { return userDir(userId).resolve("USER.md"); }
    private Path midtermPath(Long userId) { return userDir(userId).resolve("MIDTERM.md"); }

    /** 取当前用户的 UserMemory，按需懒加载。 */
    private UserMemory current() {
        Long uid = effectiveUserId();
        UserMemory um = users.computeIfAbsent(uid, k -> new UserMemory());
        if (!um.loaded) {
            um.lock.lock();
            try {
                if (!um.loaded) loadUser(uid, um);
            } finally {
                um.lock.unlock();
            }
        }
        return um;
    }

    // =========================================================================
    // 加载 / 保存
    // =========================================================================

    /** 加载（或重新加载）当前用户的记忆并刷新冻结快照。每个 session 开始时调用一次。 */
    public void loadFromDisk() {
        Long uid = effectiveUserId();
        UserMemory um = users.computeIfAbsent(uid, k -> new UserMemory());
        um.lock.lock();
        try {
            loadUser(uid, um);
        } finally {
            um.lock.unlock();
        }
    }

    /** 实际加载逻辑，调用方须持有 um.lock。 */
    private void loadUser(Long userId, UserMemory um) {
        Path dir = userDir(userId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create memory dir: " + dir, e);
        }
        ensureFileExists(memoryPath(userId));
        ensureFileExists(userPath(userId));
        ensureFileExists(midtermPath(userId));

        um.memoryEntries = deduplicate(readFile(memoryPath(userId)));
        um.userEntries = deduplicate(readFile(userPath(userId)));
        um.midtermMemory = safeReadString(midtermPath(userId));
        um.memorySnapshot = renderBlock("MEMORY", um.memoryEntries, MEMORY_CHAR_LIMIT);
        um.userSnapshot = renderBlock("USER", um.userEntries, USER_CHAR_LIMIT);
        um.midtermSnapshot = renderTextBlock("MIDTERM", um.midtermMemory, MIDTERM_CHAR_LIMIT);
        um.loaded = true;
        // 首次加载时若向量索引尚不存在，用现有条目建一次（覆盖历史遗留数据）。
        // first-load vector reindex
        if (Objects.nonNull(vectorStore) && vectorStore.isEnabled() && !vectorStore.hasIndex(userId)
                && !(um.memoryEntries.isEmpty() && um.userEntries.isEmpty())) {
            reindexVector(userId, um);
        }
    }

    /** 原子写入磁盘 */
    private void saveToDisk(Path path, List<String> entries) {
        try {
            Files.createDirectories(path.getParent());
            String content = String.join(ENTRY_DELIMITER, entries);
            Path tmp = Files.createTempFile(path.getParent(), ".mem_", ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(content);
                w.flush();
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write memory: " + path, e);
        }
    }

    private List<String> readFile(Path path) {
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(raw)) return new ArrayList<>();
            return Arrays.stream(raw.split(Pattern.quote(ENTRY_DELIMITER)))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> deduplicate(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    // =========================================================================
    // 冻结快照 — 注入 system prompt（按当前用户）
    // =========================================================================

    public String getMemorySnapshot() { return current().memorySnapshot; }
    public String getUserSnapshot() { return current().userSnapshot; }
    public String getMidtermSnapshot() { return current().midtermSnapshot; }

    /** 获取组合快照（MEMORY + USER + MIDTERM），用于注入 system prompt。 */
    public String getCombinedSnapshot() {
        UserMemory um = current();
        List<String> blocks = new ArrayList<>();
        if (!um.memorySnapshot.isEmpty()) blocks.add(um.memorySnapshot);
        if (!um.userSnapshot.isEmpty()) blocks.add(um.userSnapshot);
        if (!um.midtermSnapshot.isEmpty()) blocks.add(um.midtermSnapshot);
        return String.join("\n\n", blocks);
    }

    /**
     * 面向当前对话的记忆快照：若向量召回可用，MEMORY 笔记按 query 语义召回 Top-K（按需注入，
     * 避免全量注入稀释重点）；USER 画像与 MIDTERM 中期记忆体量小且始终相关，仍全量注入。
     * 向量不可用时回退为 {@link #getCombinedSnapshot()} 全量注入。
     */
    public String getSnapshotForQuery(String query) {
        if (Objects.isNull(vectorStore) || !vectorStore.isEnabled()) {
            return getCombinedSnapshot();
        }
        Long uid = effectiveUserId();
        UserMemory um = current();
        List<String> recalled = vectorStore.recall(uid, query);
        List<String> blocks = new ArrayList<>();
        if(Objects.nonNull(recalled) && !recalled.isEmpty()) {
            String content = String.join(ENTRY_DELIMITER, recalled);
            String sep = "═".repeat(46);
            blocks.add(sep + "\n相关记忆（按当前对话召回 " + recalled.size() + " 条）\n" + sep + "\n" + content);
        } else if (!um.memorySnapshot.isEmpty()) {
            // 无命中时回退为全量 MEMORY，避免漏掉可能相关的笔记。
            blocks.add(um.memorySnapshot);
        }
        if (!um.userSnapshot.isEmpty()) blocks.add(um.userSnapshot);
        if (!um.midtermSnapshot.isEmpty()) blocks.add(um.midtermSnapshot);
        return String.join("\n\n", blocks);
    }

    /**
     * 更新当前用户的跨会话中期记忆。该记忆不是逐条追加，而是滚动摘要：
     * 每次对话结束后由上层模型把旧摘要 + 本轮对话压缩成新的统一摘要。
     */
    public void updateMidtermMemory(String summary) {
        if (Objects.isNull(summary)) return;
        summary = summary.trim();
        if (summary.isEmpty()) return;
        if (summary.length() > MIDTERM_CHAR_LIMIT) {
            summary = summary.substring(0, MIDTERM_CHAR_LIMIT);
        }
        String scanErr = SecurityScanner.scan(summary);
        if(Objects.nonNull(scanErr)) {
            // 中期记忆来自模型归纳，若安全扫描失败则不写入，避免污染系统提示。
            return;
        }
        Long uid = effectiveUserId();
        UserMemory um = current();
        um.lock.lock();
        try {
            um.midtermMemory = summary;
            FilesWriteString(midtermPath(uid), summary);
            um.midtermSnapshot = renderTextBlock("MIDTERM", um.midtermMemory, MIDTERM_CHAR_LIMIT);
        } finally {
            um.lock.unlock();
        }
    }

    public String getRawMidtermMemory() {
        String m = current().midtermMemory;
        return Optional.ofNullable(m).orElse("");
    }

    // =========================================================================
    // 增 / 改 / 删（按当前用户，单锁覆盖 memory 与 user 两类条目）
    // =========================================================================

    /** 追加条目 */
    public Map<String, Object> add(String target, String content) {
        content = content.trim();
        if (content.isEmpty()) return result(false, "内容不能为空。", target, null, 0);

        String scanErr = SecurityScanner.scan(content);
        if (Objects.nonNull(scanErr)) return result(false, scanErr, target, null, 0);


        Long uid = effectiveUserId();
        UserMemory um = current();
        um.lock.lock();
        try {
            List<String> entries = entriesOf(um, target);
            int limit = limitOf(target);

            if (entries.contains(content))
                return result(true, "条目已存在（未重复添加）。", target, entries, limit);

            String newTotal = joinAll(entries, content);
            if (newTotal.length() > limit)
                return result(false, String.format("记忆已达 %d/%d 字符。请先替换或删除旧条目。", charCount(entries), limit), target, entries, limit);

            entries.add(content);
            setEntries(um, target, entries);
            saveToDisk(pathFor(uid, target), entries);
            reindexVector(uid, um);
            return result(true, "条目已添加。", target, entries, limit);
        } finally {
            um.lock.unlock();
        }
    }

    /** 替换条目（old_text 子串匹配） */
    public Map<String, Object> replace(String target, String oldText, String newContent) {
        oldText = oldText.trim();
        newContent = newContent.trim();
        if (oldText.isEmpty()) return result(false, "old_text 不能为空。", target, null, 0);
        if (newContent.isEmpty()) return result(false, "new_content 不能为空。使用 remove 删除条目。", target, null, 0);

        String scanErr = SecurityScanner.scan(newContent);
        if (Objects.nonNull(scanErr)) return result(false, scanErr, target, null, 0);


        Long uid = effectiveUserId();
        UserMemory um = current();
        um.lock.lock();
        try {
            List<String> entries = new ArrayList<>(entriesOf(um, target));
            int limit = limitOf(target);
            List<Integer> matchIdx = findMatches(entries, oldText);

            if (matchIdx.isEmpty()) return result(false, "没有找到匹配 '" + oldText + "' 的条目。", target, entries, limit);
            if (matchIdx.size() > 1 && !allSame(entries, matchIdx))
                return result(false, "多个条目匹配 '" + oldText + "'。请提供更具体的片段。", target, entries, limit);

            int idx = matchIdx.get(0);
            entries.set(idx, newContent);
            String total = String.join(ENTRY_DELIMITER, entries);
            if (total.length() > limit)
                return result(false, String.format("替换后将达 %d/%d 字符。请缩短内容。", total.length(), limit), target, entries, limit);

            setEntries(um, target, entries);
            saveToDisk(pathFor(uid, target), entries);
            reindexVector(uid, um);
            return result(true, "条目已替换。", target, entries, limit);
        } finally {
            um.lock.unlock();
        }
    }

    /** 删除条目（old_text 子串匹配） */
    public Map<String, Object> remove(String target, String oldText) {
        oldText = oldText.trim();
        if (oldText.isEmpty()) return result(false, "old_text 不能为空。", target, null, 0);

        Long uid = effectiveUserId();
        UserMemory um = current();
        um.lock.lock();
        try {
            List<String> entries = new ArrayList<>(entriesOf(um, target));
            int limit = limitOf(target);
            List<Integer> matchIdx = findMatches(entries, oldText);

            if (matchIdx.isEmpty()) return result(false, "没有找到匹配 '" + oldText + "' 的条目。", target, entries, limit);
            if (matchIdx.size() > 1 && !allSame(entries, matchIdx))
                return result(false, "多个条目匹配 '" + oldText + "'。请提供更具体的片段。", target, entries, limit);

            entries.remove((int) matchIdx.get(0));
            setEntries(um, target, entries);
            saveToDisk(pathFor(uid, target), entries);
            reindexVector(uid, um);
            return result(true, "条目已删除。", target, entries, limit);
        } finally {
            um.lock.unlock();
        }
    }

    /** 读取当前条目（供工具 read action 使用） */
    public List<String> readEntries(String target) {
        return new ArrayList<>(entriesOf(current(), target));
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /** 文件不存在则自动创建空文件 */
    private void ensureFileExists(Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create memory file: " + path, e);
            }
        }
    }

    private Path pathFor(Long userId, String target) {
        return "user".equals(target) ? userPath(userId) : memoryPath(userId);
    }

    private String safeReadString(Path path) {
        try {
            if (!Files.exists(path)) return "";
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void FilesWriteString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), ".midterm_", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write midterm memory: " + path, e);
        }
    }

    private List<String> entriesOf(UserMemory um, String target) {
        return "user".equals(target) ? um.userEntries : um.memoryEntries;
    }

    private void setEntries(UserMemory um, String target, List<String> entries) {
        if ("user".equals(target)) um.userEntries = entries;
        else um.memoryEntries = entries;
    }

    private int limitOf(String target) {
        return "user".equals(target) ? USER_CHAR_LIMIT : MEMORY_CHAR_LIMIT;
    }

    private static int charCount(List<String> entries) {
        return entries.isEmpty() ? 0 : String.join(ENTRY_DELIMITER, entries).length();
    }

    private static List<Integer> findMatches(List<String> entries, String text) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(text)) result.add(i);
        }
        return result;
    }

    private static boolean allSame(List<String> entries, List<Integer> idx) {
        Set<String> unique = idx.stream().map(i -> entries.get(i)).collect(Collectors.toSet());
        return unique.size() <= 1;
    }

    private static String joinAll(List<String> entries, String newEntry) {
        List<String> all = new ArrayList<>(entries);
        all.add(newEntry);
        return String.join(ENTRY_DELIMITER, all);
    }

    private static String renderBlock(String label, List<String> entries, int limit) {
        if (entries.isEmpty()) return "";
        String content = String.join(ENTRY_DELIMITER, entries);
        int pct = Math.min(100, (content.length() * 100) / limit);
        String sep = "═".repeat(46);
        String title = "USER".equals(label)
                ? String.format("用户画像 [%d%% — %d/%d 字符]", pct, content.length(), limit)
                : String.format("记忆笔记 [%d%% — %d/%d 字符]", pct, content.length(), limit);
        return sep + "\n" + title + "\n" + sep + "\n" + content;
    }

    private static String renderTextBlock(String label, String content, int limit) {
        if (StringUtils.isBlank(content)) return "";
        content = content.trim();
        int pct = Math.min(100, (content.length() * 100) / limit);
        String sep = "═".repeat(46);
        String title = "跨会话中期记忆 [%d%% — %d/%d 字符]".formatted(pct, content.length(), limit);
        return sep + "\n" + title + "\n" + sep + "\n" + content;
    }

    private static Map<String, Object> result(boolean success, String msg, String target, List<String> entries, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("target", target);
        m.put("message", msg);
        if (Objects.nonNull(entries)) {
            int current = charCount(entries);
            int pct = limit > 0 ? Math.min(100, (current * 100) / limit) : 0;
            m.put("usage", String.format("%d%% — %d/%d 字符", pct, current, limit));
            m.put("entry_count", entries.size());
        }
        return m;
    }
}