package com.miniagent.common.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 原子落盘的行为边界：**写失败时原文件必须一个字节不变**。
 *
 * <p>背景：文档工具此前用 {@code new FileOutputStream(target)} 保存，它先截断目标再写，
 * 中途失败就是"原内容已清空、新内容没写进去"。这些用例把"失败不破坏原文件"钉死，
 * 并检查临时文件不残留。</p>
 */
class AtomicFilesTest {

    @Test
    void writesContentAndReplacesExistingFile() throws IOException {
        Path dir = Files.createTempDirectory("atomic-ok");
        Path target = dir.resolve("note.txt");
        Files.writeString(target, "旧内容", StandardCharsets.UTF_8);

        AtomicFiles.write(target, out -> out.write("新内容".getBytes(StandardCharsets.UTF_8)));

        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(hasTempLeftover(dir), "成功路径不应留下临时文件");
    }

    @Test
    void failedWriteLeavesOriginalFileByteIdentical() throws IOException {
        Path dir = Files.createTempDirectory("atomic-fail");
        Path target = dir.resolve("report.docx");
        byte[] original = "原始文档内容-不可丢失".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        // 写到一半抛异常，模拟磁盘满 / 序列化失败
        assertThrows(IOException.class, () -> AtomicFiles.write(target, out -> {
            out.write("半个新文档".getBytes(StandardCharsets.UTF_8));
            throw new IOException("模拟写盘失败");
        }));

        assertArrayEquals(original, Files.readAllBytes(target),
                "写入失败后原文件必须逐字节不变（这是本类存在的唯一理由）");
        assertFalse(hasTempLeftover(dir), "失败路径应清理临时文件");
    }

    @Test
    void failedWriteOntoMissingTargetCreatesNothing() throws IOException {
        Path dir = Files.createTempDirectory("atomic-missing");
        Path target = dir.resolve("brand-new.txt");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, out -> {
            throw new IOException("模拟写盘失败");
        }));

        assertFalse(Files.exists(target), "失败时不应留下一个空的新文件");
        assertFalse(hasTempLeftover(dir), "失败路径应清理临时文件");
    }

    @Test
    void runtimeFailureFromWriterIsAlsoCleanedUp() throws IOException {
        Path dir = Files.createTempDirectory("atomic-runtime");
        Path target = dir.resolve("data.txt");
        Files.writeString(target, "keep me", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> AtomicFiles.write(target, out -> {
            throw new IllegalStateException("序列化异常");
        }));

        assertEquals("keep me", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(hasTempLeftover(dir));
    }

    private static boolean hasTempLeftover(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> temps = files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            return !temps.isEmpty();
        }
    }
}
