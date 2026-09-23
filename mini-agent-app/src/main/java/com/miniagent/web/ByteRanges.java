package com.miniagent.web;

/**
 * HTTP Range 头的手写解析器（RFC 7233 的实用子集）。
 *
 * <h2>为什么手写</h2>
 *
 * <p>控制器返回 {@code ResponseEntity<UrlResource>} 时不走
 * {@code ResourceHttpRequestHandler}，框架不替我们做 Range。这里把解析口径写死并配上测试，
 * 不依赖任何框架魔法。</p>
 *
 * <h2>口径（刻意的简化，都往「不破坏播放器」的方向偏）</h2>
 * <ul>
 *   <li>无 Range / 不认识的 range unit（非 {@code bytes}）/ 语法非法（缺 dash、非数字、
 *       {@code end < start}）→ {@link Kind#FULL}：忽略 Range，200 整段返回（RFC 允许）。</li>
 *   <li>多段（{@code bytes=0-1,5-6}）→ {@link Kind#FULL}：不实现 multipart/byteranges，
 *       整段返回。播放器拿到 200 也能正常播，只是这次拖动退化成全量下载。</li>
 *   <li>语法合法但不可满足（{@code start ≥ 文件长度}）→ {@link Kind#UNSATISFIABLE}：416。</li>
 *   <li>{@code end} 越界 → 钳到 {@code 文件长度 - 1}（RFC 要求）；{@code -N} 后缀式取末尾
 *       {@code min(N, 文件长度)} 字节；空文件的任何 Range 都按 FULL 处理（200 + 空正文）。</li>
 * </ul>
 */
public final class ByteRanges {

    /** 闭区间切片，与 Content-Range 的表述一致。 */
    public record Slice(long start, long end) {
    }

    /** 解析结论：FULL=200 整段；SLICE=206 切片；UNSATISFIABLE=416。 */
    public enum Kind {
        FULL, SLICE, UNSATISFIABLE
    }

    /** 解析结果。{@code kind == SLICE} 时 {@code slice} 非空，其余情况为 null。 */
    public record Result(Kind kind, Slice slice) {
        static final Result FULL = new Result(Kind.FULL, null);
        static final Result UNSATISFIABLE = new Result(Kind.UNSATISFIABLE, null);

        static Result slice(long start, long end) {
            return new Result(Kind.SLICE, new Slice(start, end));
        }
    }

    private ByteRanges() {
    }

    /**
     * 解析 Range 头。{@code size} 是资源字节数；返回值语义见类注释。
     */
    public static Result parse(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return Result.FULL;
        }
        String header = rangeHeader.trim();
        int eq = header.indexOf('=');
        if (eq < 0) {
            return Result.FULL;
        }
        String unit = header.substring(0, eq).trim();
        if (!"bytes".equalsIgnoreCase(unit)) {
            return Result.FULL;
        }
        String spec = header.substring(eq + 1).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            return Result.FULL;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Result.FULL;
        }
        String firstPart = spec.substring(0, dash).trim();
        String lastPart = spec.substring(dash + 1).trim();
        try {
            if (firstPart.isEmpty()) {
                // 后缀式 bytes=-N：取末尾 N 字节
                if (lastPart.isEmpty() || size <= 0) {
                    return Result.FULL;
                }
                long suffix = Long.parseLong(lastPart);
                if (suffix <= 0) {
                    return Result.FULL;
                }
                return suffix >= size ? Result.slice(0, size - 1) : Result.slice(size - suffix, size - 1);
            }
            long start = Long.parseLong(firstPart);
            if (start >= size) {
                // 语法合法但无一字节可满足 → 416；空文件按 FULL 走 200 + 空正文
                return size <= 0 ? Result.FULL : Result.UNSATISFIABLE;
            }
            long end = lastPart.isEmpty() ? size - 1 : Long.parseLong(lastPart);
            if (end < start) {
                return Result.FULL;
            }
            return Result.slice(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            return Result.FULL;
        }
    }
}
