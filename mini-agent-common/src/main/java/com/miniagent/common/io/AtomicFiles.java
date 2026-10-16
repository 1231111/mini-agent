package com.miniagent.common.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子落盘：要么写入完整的新内容，要么保持原文件一个字节不变。
 *
 * <p><b>为什么需要它</b>：直接用 {@code new FileOutputStream(target)} 会**先把目标截断**，
 * 再往里写。中间任一步失败（磁盘满、目标被别的进程占用、序列化抛异常），
 * 用户拿到的就是一个"原内容已清空、新内容没写进去"的文件 —— 对文档编辑工具而言这就是
 * 用户资产丢失，而且无法回滚。</p>
 *
 * <p>本类把项目里已经重复写了三遍的写法收敛成一处（{@code MemoryStore} 的记忆文件与
 * 中期记忆文件、{@code ConversationStore} 的会话文件）：<b>同目录建临时文件 → 写 →
 * 原子移动覆盖</b>。同目录是必须的 —— 跨卷的 {@code ATOMIC_MOVE} 不成立；
 * 移动失败时会退回普通替换，保证可用性优先。</p>
 *
 * <p>临时文件名由<b>目标文件名派生</b>，不写死前缀：临时文件与目标同生命周期，
 * 名字能对应上，出问题时一眼看得出是谁留下的。</p>
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** 往流里写内容。允许抛 IOException，异常会被当作"写入失败"处理（原文件保持不动）。 */
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * 原子写入：成功则 {@code target} 内容整体替换，失败则 {@code target} 保持原样（或不存在）。
     *
     * @throws IOException 写入或移动失败；此时临时文件已被清理，目标未被触碰
     */
    public static void write(Path target, ContentWriter writer) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("写入目标不能为空");
        }
        if (writer == null) {
            throw new IllegalArgumentException("写入内容不能为空");
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        if (dir == null) {
            throw new IOException("写入目标没有父目录，无法做原子替换: " + absolute);
        }
        Files.createDirectories(dir);

        Path tmp = Files.createTempFile(dir, "." + absolute.getFileName() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.writeTo(out);
                out.flush();
            }
            move(tmp, absolute);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 同一目录下通常支持原子移动；不支持时退化为普通替换，可用性优先。
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不该覆盖掉真正的失败原因；残留的 .tmp 也不会破坏目标文件。
        }
    }
}
