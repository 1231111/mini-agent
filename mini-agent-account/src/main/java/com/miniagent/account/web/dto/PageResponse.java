package com.miniagent.account.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应。
 *
 * <p>不直接序列化 Spring Data 的 {@link Page}：它的 JSON 形状随版本变化
 * （{@code content} 与 {@code pageable} 嵌套在 3.x 已调整），而调用方要的是稳定契约。</p>
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <E, R> PageResponse<R> of(Page<E> source, Function<E, R> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
