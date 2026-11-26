package com.miniagent.agent.tool;

import com.google.re2j.Pattern;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Linear-time regex search with explicit scan, memory, output and wall-clock budgets. */
final class BoundedCodeSearch {
    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_FILES = 2_000;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SCAN_BYTES = 64L * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 512 * 1024;
    private static final int MAX_OUTPUT_CHARS = 3_500;
    private static final Set<String> IGNORED_DIRS = Set.of(".git", "target", "node_modules", ".idea", "build", "dist");
    private static final Set<String> BINARY_EXTENSIONS = Set.of("class", "jar", "war", "zip", "gz", "tar", "png",
            "jpg", "jpeg", "gif", "ico", "pdf", "exe", "dll", "so", "bin", "mp4", "mp3", "woff", "woff2", "ttf", "lock");

    private BoundedCodeSearch() { }

    static String search(String expression, Path root, String glob, int maxResults, Duration timeout) {
        final Pattern pattern;
        final java.nio.file.PathMatcher fileFilter;
        try {
            pattern = Pattern.compile(expression);
            fileFilter = glob == null || glob.isBlank() ? null : root.getFileSystem().getPathMatcher("glob:" + glob);
        } catch (RuntimeException invalid) {
            return BuiltinTools.errorJson(ToolErrorCode.INVALID_ARGUMENT,
                    "搜索表达式或 glob 无效（使用 RE2 正则，不支持回溯引用/前后向断言）: " + invalid.getMessage());
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder output = new StringBuilder();
        int[] entries = {0};
        int[] files = {0};
        int[] matches = {0};
        long[] bytes = {0};
        boolean[] partial = {false};
        try {
            PathGuard.assertAllowed(root, "search");
            Files.walkFileTree(root, Set.of(), 64, new SimpleFileVisitor<>() {
                private boolean exhausted() throws InterruptedIOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("搜索已取消");
                    if (System.nanoTime() >= deadline) throw new InterruptedIOException("搜索超过 deadline");
                    boolean limit = entries[0] >= MAX_ENTRIES || files[0] >= MAX_FILES
                            || bytes[0] >= MAX_SCAN_BYTES || matches[0] >= maxResults
                            || output.length() >= MAX_OUTPUT_CHARS;
                    if (limit) partial[0] = true;
                    return limit;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (exhausted()) return FileVisitResult.TERMINATE;
                    entries[0]++;
                    return !PathGuard.isAllowed(directory)
                            || (!directory.equals(root) && IGNORED_DIRS.contains(directory.getFileName().toString()))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (exhausted()) return FileVisitResult.TERMINATE;
                    entries[0]++;
                    if (!attributes.isRegularFile() || !PathGuard.isAllowed(file) || binary(file)
                            || (fileFilter != null && !fileFilter.matches(file.getFileName()))) return FileVisitResult.CONTINUE;
                    if (attributes.size() > MAX_FILE_BYTES || bytes[0] + attributes.size() > MAX_SCAN_BYTES) {
                        partial[0] = true;
                        return FileVisitResult.CONTINUE;
                    }
                    files[0]++;
                    long allowance = Math.min(MAX_FILE_BYTES, MAX_SCAN_BYTES - bytes[0]);
                    CountingInput input = new CountingInput(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), allowance);
                    try (input;
                         BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                                 StandardCharsets.UTF_8.newDecoder()), 8192)) {
                        StringBuilder line = new StringBuilder();
                        int lineNumber = 1;
                        boolean lineTooLong = false;
                        int ch;
                        while ((ch = reader.read()) != -1) {
                            if ((input.count & 8191) == 0 && exhausted()) break;
                            if (ch == '\r' || ch == '\n') {
                                if (ch == '\r') {
                                    reader.mark(1);
                                    if (reader.read() != '\n') reader.reset();
                                }
                                if (lineTooLong) partial[0] = true;
                                else addMatch(file, lineNumber, line.toString());
                                line.setLength(0);
                                lineTooLong = false;
                                lineNumber++;
                                if (exhausted()) break;
                            } else if (line.length() < MAX_LINE_CHARS) line.append((char) ch);
                            else lineTooLong = true;
                        }
                        if (lineTooLong) partial[0] = true;
                        else if (!line.isEmpty() && !exhausted()) addMatch(file, lineNumber, line.toString());
                    } catch (java.nio.charset.CharacterCodingException invalidText) {
                        partial[0] = true;
                    } catch (ScanLimitException limit) {
                        partial[0] = true;
                    } finally {
                        bytes[0] += input.count;
                    }
                    return exhausted() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                private void addMatch(Path file, int lineNumber, String line) {
                    var matcher = pattern.matcher(line);
                    if (!matcher.find()) return;
                    int from = Math.max(0, matcher.start() - 80);
                    if (from > 0 && Character.isLowSurrogate(line.charAt(from))) from--;
                    int to = Math.min(line.length(), from + 240);
                    if (to < line.length() && to > from && Character.isHighSurrogate(line.charAt(to - 1))) to--;
                    int charOffset = line.codePointCount(0, from);
                    String relative = root.equals(file) ? file.getFileName().toString() : root.relativize(file).toString();
                    String result = relative.replace('\\', '/') + ":" + lineNumber + ":"
                            + line.substring(from, to) + (line.length() > 240 ? " [char_offset=" + charOffset + "]" : "") + "\n";
                    if (output.length() + result.length() > MAX_OUTPUT_CHARS) { partial[0] = true; return; }
                    output.append(result);
                    matches[0]++;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    partial[0] = true;
                    return FileVisitResult.CONTINUE;
                }
            });
            return "[search_code engine=RE2/J matches=" + matches[0] + " files_scanned=" + files[0]
                    + " bytes_scanned=" + bytes[0] + " partial=" + partial[0] + "]\n"
                    + (output.isEmpty() ? (partial[0] ? "未找到匹配；部分文件未扫描，请缩小 path/glob。" : "（无匹配）") : output);
        } catch (InterruptedIOException stopped) {
            boolean cancelled = Thread.currentThread().isInterrupted();
            return BuiltinTools.errorJson(cancelled ? ToolErrorCode.CANCELLED : ToolErrorCode.TIMEOUT, stopped.getMessage());
        } catch (SecurityException denied) {
            return BuiltinTools.errorJson(ToolErrorCode.PERMISSION_DENIED, denied.getMessage());
        } catch (IOException failed) {
            return BuiltinTools.errorJson(ToolErrorCode.EXECUTION_FAILED, "搜索失败: " + failed.getMessage());
        }
    }

    private static boolean binary(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static final class ScanLimitException extends IOException { }

    private static final class CountingInput extends FilterInputStream {
        private final long maximum;
        private long count;
        CountingInput(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            if (count >= maximum) {
                if (super.read() >= 0) throw new ScanLimitException();
                return -1;
            }
            int next = super.read();
            if (next >= 0) count++;
            return next;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (count >= maximum) return read();
            int result = super.read(buffer, offset, (int) Math.min(length, maximum - count));
            if (result > 0) count += result;
            return result;
        }
    }
}
