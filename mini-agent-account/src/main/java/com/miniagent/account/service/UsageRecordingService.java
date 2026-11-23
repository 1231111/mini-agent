package com.miniagent.account.service;

import com.miniagent.account.entity.UserDailyUsage;
import com.miniagent.account.repository.UserDailyUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用量累计写入。
 *
 * <p>写入方（agent）负责把「日期」和「金额」算好再传，账号服务不做时区换算、不查单价：
 * 这两件事一旦放到服务端，调价和改时区都会重写历史账单。</p>
 *
 * <p>并发语义：先尝试原子累加；没有命中行才插入；插入撞唯一键说明另一个请求刚建好行，
 * 回到累加即可。整个过程不持有跨请求的锁，也不依赖 {@code ON DUPLICATE KEY UPDATE}
 * ——后者在 H2 的 MySQL 兼容模式下不可用，会在测试环境炸而生产不炸。</p>
 */
@Service
public class UsageRecordingService {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingService.class);

    @Autowired
    private UserDailyUsageRepository repository;

    /**
     * 累加一条用量。
     *
     * @param usageDay 由调用方按其配额时区算好的自然日
     * @param model    模型名；null 或空按空串记，保证唯一键可去重
     * @param costMicros 百万分之一元，由调用方按当时单价算好
     * @return true 表示已计入；false 表示参数不可用（调用方应记录并告警，不要静默丢弃）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(Long userId, LocalDate usageDay, String model,
                          long inputTokens, long cachedInputTokens,
                          long outputTokens, long totalTokens,
                          long costMicros, long eventCount) {
        if (userId == null || usageDay == null) {
            log.warn("用量写入缺少 userId 或日期，已丢弃: userId={} day={}", userId, usageDay);
            return false;
        }
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.length() > 64) normalizedModel = normalizedModel.substring(0, 64);

        long input = Math.max(0, inputTokens);
        long cached = Math.min(Math.max(0, cachedInputTokens), input);
        long output = Math.max(0, outputTokens);
        long total = totalTokens > 0 ? totalTokens : input + output;
        long cost = Math.max(0, costMicros);
        long events = Math.max(0, eventCount);

        int updated = repository.increment(userId, usageDay, normalizedModel,
                input, cached, output, total, cost, events, LocalDateTime.now());
        if (updated > 0) return true;

        UserDailyUsage row = new UserDailyUsage();
        row.setUserId(userId);
        row.setUsageDay(usageDay);
        row.setModel(normalizedModel);
        row.setInputTokens(input);
        row.setCachedInputTokens(cached);
        row.setOutputTokens(output);
        row.setTotalTokens(total);
        row.setCostMicros(cost);
        row.setEventCount(events);
        try {
            repository.saveAndFlush(row);
            return true;
        } catch (DataIntegrityViolationException concurrentInsert) {
            // 另一个写入方刚建好这一行；回到累加路径，不要重试插入。
            int retried = repository.increment(userId, usageDay, normalizedModel,
                    input, cached, output, total, cost, events, LocalDateTime.now());
            if (retried == 0) {
                log.error("用量累加在并发插入后仍未命中: userId={} day={} model={}",
                        userId, usageDay, normalizedModel);
                return false;
            }
            return true;
        }
    }
}
