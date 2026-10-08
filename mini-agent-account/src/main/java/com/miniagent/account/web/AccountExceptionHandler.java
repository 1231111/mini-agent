package com.miniagent.account.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 账号服务的异常收敛点。
 *
 * <p>与云端 agent 的 {@code GlobalExceptionHandler} 保持同一套对外语义：
 * 业务失败仍是 <b>HTTP 200</b> + {@code success:false} + 错误码。
 *
 * <p>这条约定不是风格问题：客户机的 {@code CloudAccountClient.parse()} 正是据此判断成败的
 * （先看 HTTP 是否可读，再看 {@code success} 字段）。若这里把业务失败改成 4xx，
 * 那边的 {@code RestClientResponseException} 分支会先命中，把所有业务错误
 * ——包括"用户名已被占用"——统一报成"云端账号服务拒绝该请求"，
 * 用户看到的就是一条说不出该怎么办的错误。
 */
@RestControllerAdvice
public class AccountExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountExceptionHandler.class);

    /** 业务失败：错误码原样返回，HTTP 仍为 200。 */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 其余异常一律 500。
     *
     * <p>{@code IllegalStateException} 会落到这里 —— 服务层用它表示"数据不一致"
     * （例如订阅引用了不存在的套餐）。那类问题不该被包装成业务错误返回 200：
     * 它需要被看见、被修，而不是被调用方当成一次正常的业务失败重试。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("账号服务未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_INTERNAL_ERROR));
    }
}
