package com.miniagent.agent.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Validates file provenance before a browser action can transfer bytes to a website. */
public final class BrowserUploadFiles {
    public static final int MAX_FILES = 10;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private BrowserUploadFiles() {}

    public static List<Path> resolve(Path workspace, Path ownedUploads, List<String> requested) throws IOException {
        if (requested == null || requested.isEmpty() || requested.size() > MAX_FILES)
            throw new IllegalArgumentException("请选择1至10个文件；总大小不能超过50MiB");
        Path root = workspace.toAbsolutePath().normalize();
        Path uploadRoot = ownedUploads == null ? null : ownedUploads.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        long bytes = 0;
        for (String value : requested) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("上传文件路径不能为空");
            String normalized = value.trim().replace('\\', '/');
            boolean uploadAlias = normalized.startsWith("user-uploads/");
            if (uploadAlias && uploadRoot == null) throw new SecurityException("当前用户上传目录不可用");
            if (uploadAlias && normalized.contains("?")) normalized = normalized.substring(0, normalized.indexOf('?'));
            Path input = uploadAlias ? uploadRoot.resolve(normalized.substring("user-uploads/".length()))
                    : Path.of(com.miniagent.agent.tool.BuiltinTools.stripWorkspaceAlias(normalized));
            Path candidate = (input.isAbsolute() ? input : root.resolve(input)).toAbsolutePath().normalize();
            Path allowedRoot = candidate.startsWith(root) ? root :
                    uploadRoot != null && candidate.startsWith(uploadRoot) ? uploadRoot : null;
            if (allowedRoot == null) throw new SecurityException("仅允许当前会话工作区或当前用户上传目录内的文件");
            Path real = candidate.toRealPath();
            if (!real.startsWith(allowedRoot.toRealPath()) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(real)) throw new SecurityException("上传路径越界、为链接或不是可读普通文件");
            if (files.contains(real)) throw new IllegalArgumentException("上传列表不能重复包含同一文件");
            bytes = Math.addExact(bytes, Files.size(real));
            if (bytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("上传文件总大小超过50MiB");
            files.add(real);
        }
        return List.copyOf(files);
    }
}
