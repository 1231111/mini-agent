package com.miniagent.account.web;

import com.miniagent.account.service.UsageQueryService;
import com.miniagent.account.service.UsageRecordingService;
import com.miniagent.account.web.dto.UsageSummaryResponse;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 用量查询与写入。
 *
 * <p>挂在 {@code /api/membership/} 前缀下，因此自动被 {@link InternalKeyFilter} 保护。
 * 账号服务不签发用户 token、也没有「当前登录用户」，{@code userId} 是调用方（agent）
 * 验完用户身份后担保传过来的 —— 这个接口不负责证明「这个 userId 就是你」。</p>
 *
 * <p>如果要给前端直接调用，必须由 agent 侧代理：先验用户 token，再带内部密钥转发，
 * 不能让浏览器直连账号服务，否则任何人改个 {@code userId} 就能看别人的用量。</p>
 */
@RestController
@RequestMapping("/api/membership/usage")
public class AccountUsageController {

    @Autowired
    private UsageQueryService queries;
    @Autowired
    private UsageRecordingService recorder;

    /**
     * 汇总查询。
     *
     * @param userId 调用方担保的用户 id
     * @param from   起始日（含），缺省为 {@code to} 前 29 天
     * @param to     结束日（含），缺省为今天
     */
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UsageSummaryResponse> summary(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return ApiResponse.ok(queries.summary(userId, from, to));
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, invalid.getMessage());
        }
    }

    public record RecordRequest(
            Long userId,
            LocalDate usageDay,
            String model,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            Long costMicros,
            Long eventCount) {
    }

    /**
     * 累加一条用量。
     *
     * <p>语义是<b>累加</b>，不是覆盖。调用方必须保证同一次账单事件只上报一次；
     * 目前没有 event_id 唯一键，重复上报会重复计数。要做严格幂等，需要在这张表上
     * 加 {@code event_id} 并用唯一索引兜底 —— 这是下一步，不是当前接口的承诺。</p>
     *
     * @param costMicros 百万分之一元，调用方按当时单价算好
     */
    @PostMapping(value = "/record", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> record(@RequestBody(required = false) RecordRequest body) {
        if (body == null || body.userId() == null || body.usageDay() == null) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "userId 与 usageDay 必填");
        }
        boolean accepted = recorder.record(body.userId(), body.usageDay(), body.model(),
                valueOrZero(body.inputTokens()),
                valueOrZero(body.cachedInputTokens()),
                valueOrZero(body.outputTokens()),
                valueOrZero(body.totalTokens()),
                valueOrZero(body.costMicros()),
                body.eventCount() == null ? 1 : Math.max(1, body.eventCount()));
        return accepted
                ? ApiResponse.ok(null)
                : ApiResponse.fail(ErrorCode.CONFIG_INVALID, "用量未被接受，请检查参数");
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
