package com.miniagent.agent.tool;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/** Validates a workspace-owned PNG/JPEG before it is projected as model-visible visual evidence. */
public final class WorkspaceImageInspection {
    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 32_000_000;

    private WorkspaceImageInspection() {}

    public record Descriptor(Path path, String mimeType, int width, int height, long sizeBytes) {}

    public static Descriptor inspect(Path workspaceRoot, String rawPath) throws IOException {
        if (workspaceRoot == null || rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("请提供 workspace 内的图片路径");
        }
        Path root = workspaceRoot.toRealPath();
        Path supplied = Path.of(rawPath.trim());
        Path candidate = supplied.isAbsolute() ? supplied : root.resolve(supplied);
        candidate = candidate.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("为避免路径跳转，image_inspect 不接受符号链接");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("image_inspect 只允许读取当前 workspace 内的普通文件");
        }

        byte[] bytes;
        try (FileChannel channel = FileChannel.open(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片为空或超过 8 MiB 的视觉检查上限");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded read */ }
            if (buffer.position() != size) throw new IOException("图片在读取期间发生变化");
            bytes = new byte[(int) size];
            buffer.flip();
            buffer.get(bytes);
        }

        String mimeType = imageMime(bytes);
        if (mimeType == null) {
            throw new IllegalArgumentException("仅支持按文件内容识别的 PNG/JPEG 图片，不接受扩展名伪装");
        }
        int width;
        int height;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片数据无法解码");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("图片尺寸超过视觉检查上限");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new IllegalArgumentException("图片数据不完整，不能作为验收证据");
                }
            } finally {
                reader.dispose();
            }
        }
        return new Descriptor(real, mimeType, width, height, bytes.length);
    }

    static String imageMime(byte[] bytes) {
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes == null || bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != (prefix[i] & 0xff)) return false;
        }
        return true;
    }
}
