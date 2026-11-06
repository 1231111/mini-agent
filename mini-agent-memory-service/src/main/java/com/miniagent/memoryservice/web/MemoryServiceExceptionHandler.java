package com.miniagent.memoryservice.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.common.exception.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 与账号服务同一语义：业务失败 HTTP 200 + {@code success:false} + 错误码；
 * 系统故障 500。App 侧客户端据此区分「可提示用户」与「依赖故障」。
 */
@RestControllerAdvice
public class MemoryServiceExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MemoryServiceExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> business(BusinessException e) {
        log.info("业务失败 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception e) {
        log.info("请求参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.MEMORY_REQUEST_INVALID, e.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<Void>> system(SystemException e) {
        log.error("系统失败 code={}", e.getErrorCode().getCode(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("记忆服务未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_INTERNAL_ERROR));
    }
}
