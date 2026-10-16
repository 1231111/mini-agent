package com.miniagent.agent.tool;

import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 读取本机图片的唯一入口：先把路径关进允许的根，再按**内容**判类型，最后限大小。
 *
 * <p><b>为什么需要它</b>：早期读本机图片的工具（ComfyUI 系：{@code comfyui_check_quality}、
 * {@code comfyui_execute(upload)} / img2img / img2video，均已随 ComfyUI 能力一并下线）此前把模型给的
 * {@code image_path} 直接交给 {@code Files.readAllBytes}：任意绝对路径可读，读到的字节还会被 base64
 * 送进模型或远端服务。等于给模型开了一条"读主机任意文件并外送"的通道 ——
 * 同模块的 {@code read_file} / {@code edit_file} 都过了 {@link PathGuard}，只有这里没有。</p>
 *
 * <p><b>三道闸，缺一不可</b>：</p>
 * <ol>
 *   <li><b>路径</b>：媒体库相对路径（{@link MediaStorage#resolve}）或落在允许根内的路径
 *       （{@link PathGuard#assertAllowed}）。允许的根由配置决定（{@code miniagent.data.dir}、
 *       workspace、项目根、{@code agent.tools.allowed-read-roots}），代码里不写死任何目录。</li>
 *   <li><b>类型</b>：按 magic bytes 判定，不看扩展名 —— 否则把 {@code id_rsa} 改名 {@code .png}
 *       就能骗过去。</li>
 *   <li><b>大小</b>：{@code agent.tools.local-media.max-bytes}，默认 50MB（与附件提取同口径）。
 *       需要一个数字时改配置，不要改代码。</li>
 * </ol>
 */
@Slf4j
@Component
public class LocalMediaReader {

    /**
     * 单张本地图片读取上限，默认与 {@code file.extract.max-bytes}（50MB）同口径。
     *
     * <p>字段初始化器和 {@code @Value} 的默认值必须是同一个常量：只写在注解里的话，
     * 脱离 Spring 构造出来的实例（单元测试、独立工具类）拿着的是 {@code 0}，
     * 于是每张图都撞"超过上限" —— 闸门「默认放行、配置收紧」，不该是「默认全拒」。</p>
     */
    static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;

    @Value("${agent.tools.local-media.max-bytes:" + DEFAULT_MAX_BYTES + "}")
    private long maxBytes = DEFAULT_MAX_BYTES;

    private final MediaStorage mediaStorage;

    public LocalMediaReader(@Autowired(required = false) MediaStorage mediaStorage) {
        this.mediaStorage = mediaStorage;
    }

    /** 读到的图片内容。 */
    public record Image(Path path, byte[] bytes, String mimeType) {
    }

    /**
     * 解析为绝对路径：媒体库相对路径优先，其次绝对路径必须落在允许根内。
     *
     * @throws SecurityException        路径在允许的根之外（消息里带放行方式）
     * @throws IllegalArgumentException 参数为空 / 文件不存在
     */
    public Path resolve(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw new IllegalArgumentException("图片路径不能为空");
        }
        String raw = rawPath.trim();
        if (mediaStorage != null) {
            try {
                Path inMedia = mediaStorage.resolve(raw);
                if (Objects.nonNull(inMedia) && Files.exists(inMedia)) {
                    PathGuard.assertAllowed(inMedia, "read");
                    return inMedia;
                }
            } catch (SecurityException e) {
                throw e;
            } catch (RuntimeException e) {
                log.debug("媒体库解析失败，退回按路径处理: {} ({})", raw, e.getMessage());
            }
        }
        Path candidate;
        try {
            candidate = Path.of(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("图片路径无法解析: " + raw);
        }
        PathGuard.assertAllowed(candidate, "read");
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("图片文件不存在: " + raw);
        }
        return candidate;
    }

    /** 读入图片字节；非图片内容一律拒绝。 */
    public Image read(String rawPath) {
        return read(resolve(rawPath));
    }

    /** 读入图片字节；路径未过闸时同样拒绝（供已经是 Path 的调用点使用）。 */
    public Image read(Path path) {
        PathGuard.assertAllowed(path, "read");
        try {
            long size = Files.size(path);
            if (size <= 0) {
                throw new IllegalArgumentException("图片文件为空: " + path);
            }
            if (size > maxBytes) {
                throw new IllegalArgumentException("图片超过读取上限（" + maxBytes + " 字节，当前 "
                        + size + "），需要调整请改 agent.tools.local-media.max-bytes: " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            String mime = sniffImageMime(bytes);
            if (mime == null) {
                throw new IllegalArgumentException(
                        "不是受支持的图片格式（按文件内容判定，不看扩展名）: " + path);
            }
            return new Image(path, bytes, mime);
        } catch (IOException e) {
            throw new IllegalStateException("读取图片失败: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * 按文件头判定图片类型；不是图片返回 {@code null}。
     *
     * <p>每种格式只要求判定自己需要的那几个字节，不统一卡一个长度 ——
     * 一刀切会把合法的短输入误判成"不是图片"（GIF 头 6 字节、BMP 头 2 字节），
     * 而误判的后果是拒绝一张真图片。真正需要更多字节的只有 WebP（第 8~11 字节）。
     */
    static String sniffImageMime(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(bytes, 'B', 'M')) {
            return "image/bmp";
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && startsWithAt(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        return null;
    }

    /** 从偏移 0 起逐字节比对（长度不足即不匹配）。 */
    private static boolean startsWith(byte[] bytes, int... expected) {
        return startsWithAt(bytes, 0, expected);
    }

    private static boolean startsWithAt(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
