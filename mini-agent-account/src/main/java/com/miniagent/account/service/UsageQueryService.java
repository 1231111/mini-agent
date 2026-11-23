package com.miniagent.account.service;

import com.miniagent.account.entity.UserDailyUsage;
import com.miniagent.account.repository.UserDailyUsageRepository;
import com.miniagent.account.web.dto.UsageSummaryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用量查询。
 *
 * <p>查询侧只做聚合，不做计价、不做时区换算：这两个口径由写入方固化在行里，
 * 查询时重算会让同一个日期在不同时间查出来的金额不一样。</p>
 */
@Service
public class UsageQueryService {

    /** 单次查询的最大跨度。防止一个 from=1970 的请求把全表拉进内存。 */
    static final int MAX_RANGE_DAYS = 366;

    @Autowired
    private UserDailyUsageRepository repository;

    @Transactional(readOnly = true)
    public UsageSummaryResponse summary(Long userId, LocalDate from, LocalDate to) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("起始日期不能晚于结束日期");
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end))
            throw new IllegalArgumentException("查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");

        List<UserDailyUsage> rows =
                repository.findByUserIdAndUsageDayBetweenOrderByUsageDayAscModelAsc(userId, start, end);

        long input = 0, cached = 0, output = 0, total = 0, cost = 0, events = 0;

        Map<String, long[]> modelBuckets = new LinkedHashMap<>();
        Map<LocalDate, long[]> dayBuckets = new TreeMap<>();

        for (UserDailyUsage row : rows) {
            input += row.getInputTokens();
            cached += row.getCachedInputTokens();
            output += row.getOutputTokens();
            total += row.getTotalTokens();
            cost += row.getCostMicros();
            events += row.getEventCount();

            long[] byModel = modelBuckets.computeIfAbsent(row.getModel(), ignored -> new long[6]);
            accumulate(byModel, row);

            long[] byDay = dayBuckets.computeIfAbsent(row.getUsageDay(), ignored -> new long[6]);
            accumulate(byDay, row);
        }

        List<UsageSummaryResponse.ModelUsage> models = new ArrayList<>(modelBuckets.size());
        modelBuckets.forEach((model, values) -> models.add(new UsageSummaryResponse.ModelUsage(
                model, values[0], values[1], values[2], values[3], values[4], values[5])));

        List<UsageSummaryResponse.DayUsage> days = new ArrayList<>(dayBuckets.size());
        dayBuckets.forEach((day, values) -> days.add(new UsageSummaryResponse.DayUsage(
                day, values[0], values[1], values[2], values[3], values[4], values[5])));

        return new UsageSummaryResponse(userId, start, end,
                input, cached, output, total, cost, events, dayBuckets.size(), models, days);
    }

    /** 索引：0 input / 1 cached / 2 output / 3 total / 4 cost / 5 eventCount。 */
    private static void accumulate(long[] bucket, UserDailyUsage row) {
        bucket[0] += row.getInputTokens();
        bucket[1] += row.getCachedInputTokens();
        bucket[2] += row.getOutputTokens();
        bucket[3] += row.getTotalTokens();
        bucket[4] += row.getCostMicros();
        bucket[5] += row.getEventCount();
    }
}
