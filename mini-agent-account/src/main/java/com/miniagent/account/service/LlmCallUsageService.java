package com.miniagent.account.service;

import com.miniagent.account.entity.UserLlmCallUsage;
import com.miniagent.account.repository.UserLlmCallUsageRepository;
import com.miniagent.account.web.dto.LatencyStatsResponse;
import com.miniagent.account.web.dto.LlmCallUsageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 单次调用明细的写入与查询。
 *
 * <p>写入按 {@code requestId} 幂等：同一次模型调用被重复上报（重试、断线重连、
 * outbox 重复投递）时只留一行。判定靠唯一索引，不靠先查后插 —— 并发时后者会双写。</p>
 */
@Service
public class LlmCallUsageService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallUsageService.class);

    /** 分页上限：明细是排障用的，不需要一次拉几千行。 */
    public static final int MAX_PAGE_SIZE = 200;

    @Autowired
    private UserLlmCallUsageRepository repository;

    public record RecordCommand(
            Long userId,
            String sessionId,
            String requestId,
            String model,
            String reasoningEffort,
            String endpoint,
            boolean streaming,
            String billingMode,
            String apiKeyHint,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            long costMicros,
            long ttftMillis,
            long durationMillis,
            String userAgent) {
    }

    /**
     * 记录一次调用。
     *
     * @return true 表示已记录（含重复上报）；false 表示参数不可用，调用方应告警
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(RecordCommand command) {
        if (command == null || command.userId() == null
                || command.requestId() == null || command.requestId().isBlank()) {
            log.warn("调用明细缺少 userId 或 requestId，已丢弃");
            return false;
        }
        String requestId = truncate(command.requestId(), 128);
        if (repository.existsByRequestId(requestId)) return true;

        UserLlmCallUsage row = new UserLlmCallUsage();
        row.setUserId(command.userId());
        row.setSessionId(truncate(command.sessionId(), 100));
        row.setRequestId(requestId);
        row.setModel(truncate(command.model(), 64));
        row.setReasoningEffort(truncate(command.reasoningEffort(), 32));
        row.setEndpoint(truncate(command.endpoint(), 64));
        row.setStreaming(command.streaming());
        row.setBillingMode(truncate(command.billingMode(), 16));
        row.setApiKeyHint(truncate(command.apiKeyHint(), 16));

        long input = Math.max(0, command.inputTokens());
        row.setInputTokens(input);
        // 负数表示调用方没拿到缓存明细（上游未返回 details），存 NULL 而不是 0。
        // 存 0 会被当成「确认零命中」，下游按全价计费且无法自证。
        long cachedRaw = command.cachedInputTokens();
        row.setCachedInputTokens(cachedRaw < 0 ? null : Math.min(cachedRaw, input));
        row.setOutputTokens(Math.max(0, command.outputTokens()));
        row.setTotalTokens(command.totalTokens() > 0
                ? command.totalTokens() : input + Math.max(0, command.outputTokens()));
        row.setCostMicros(Math.max(0, command.costMicros()));

        // 非流式没有首 token；调用方若仍传了值，保留它但标记流式与否，查询时按 streaming 区分。
        row.setTtftMillis(Math.max(0, command.ttftMillis()));
        row.setDurationMillis(Math.max(0, command.durationMillis()));
        row.setUserAgent(truncate(command.userAgent(), 256));

        try {
            repository.saveAndFlush(row);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // 并发下另一个请求先写入了同一 requestId；视为已记录。
            return true;
        }
    }

    @Transactional(readOnly = true)
    public Page<LlmCallUsageResponse> detail(Long userId, LocalDate from, LocalDate to, int page, int size) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("起始日期不能晚于结束日期");
        if (start.plusDays(UsageQueryService.MAX_RANGE_DAYS).isBefore(end))
            throw new IllegalArgumentException("查询跨度不能超过 " + UsageQueryService.MAX_RANGE_DAYS + " 天");

        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(0, page), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                userId, start.atStartOfDay(), end.atTime(LocalTime.MAX), pageable)
                .map(LlmCallUsageResponse::of);
    }

    @Transactional(readOnly = true)
    public LatencyStatsResponse latency(Long userId, LocalDate from, LocalDate to) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("起始日期不能晚于结束日期");

        var rows = repository.latencyStats(userId, start.atStartOfDay(), end.atTime(LocalTime.MAX));
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return new LatencyStatsResponse(0, 0, 0, 0, 0);
        }
        Object[] row = rows.get(0);
        return new LatencyStatsResponse(
                asLong(row, 0),
                asLong(row, 4),
                asDouble(row, 1),
                asDouble(row, 2),
                asLong(row, 3));
    }

    private static long asLong(Object[] row, int index) {
        if (index >= row.length || row[index] == null) return 0L;
        return ((Number) row[index]).longValue();
    }

    private static double asDouble(Object[] row, int index) {
        if (index >= row.length || row[index] == null) return 0d;
        return ((Number) row[index]).doubleValue();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
