package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.common.exception.SystemException;
import com.miniagent.config.cloud.CloudAccountException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全统异常处理器：所有异常统一转换为 {@link ApiResponse} 格式返回。
 * <p>
 * 异常优先级（从高到低）：
 * <ol>
 *   <li>BusinessException — 业务校验失败，已携带 ErrorCode</li>
 *   <li>SystemException — 基础设施/IO/外部调用失败，已携带 ErrorCode</li>
 *   <li>MaxUploadSizeExceededException — 上传超限</li>
 *   <li>IllegalArgumentException — 参数校验失败</li>
 *   <li>IllegalStateException — 状态非法</li>
 *   <li>Exception — 兜底，系统内部错误</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常 [{}]: {}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    // ==================== 系统异常 ====================

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleSystem(SystemException e) {
        log.error("系统异常 [{}]: {}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    // ==================== 上游账号/会员服务异常 ====================

    /**
     * 账号服务返回的业务失败。
     *
     * <p><b>刻意用 HTTP 200 而不是 4xx/5xx。</b>这一条与 {@code /api/tokens} 的既有行为保持一致：
     * 调用账号服务失败时，错误信息在 {@code code} 里（如 {@code MEMBER.01.01} 套餐不存在、
     * {@code AUTH.03.01} 账号服务连不上），前端据 {@code success:false} 分流。
     *
     * <p>若在这里给 4xx，前端会把它当成"自己的请求写错了"去改请求体；
     * 给 5xx 则会被兜底分支吞成"系统内部错误，请稍后重试"—— 那样"套餐已下架"
     * 这种能自助解决的信息就丢了，用户只会反复重试。
     *
     * <p>注意这里<b>不是</b> {@code BusinessException} 那条路径：后者返回 400，
     * 因为那是"你传的参数不合法"，确实该由调用方改。
     */
    @ExceptionHandler(CloudAccountException.class)
    public ResponseEntity<ApiResponse<Void>> handleCloudAccount(CloudAccountException e) {
        log.warn("账号服务调用失败 [{}]: {}", e.errorCode().getCode(), e.getMessage());
        return ResponseEntity.ok(ApiResponse.fail(e.errorCode(), e.getMessage()));
    }

    // ==================== 上传超限 ====================

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传超过大小限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE, MessageConstants.FILE_TOO_LARGE));
    }

    // ==================== 参数校验 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.CONFIG_INVALID, e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.CONFIG_INVALID, "缺少参数: " + e.getParameterName()));
    }

    // ==================== 状态非法 ====================

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("状态非法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_INTERNAL_ERROR, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("访问被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN));
    }

    // ==================== 静态资源缺失（如浏览器自动请求 favicon.ico）====================

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    // ==================== SSE 异步超时（连接到期，不是业务崩溃）====================

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e, HttpServletRequest request) {
        log.warn("SSE 连接超时: {} {}", request.getMethod(), request.getRequestURI());
    }

    // ==================== SSE 连接中断（客户端断开，非服务端错误）====================

    @ExceptionHandler(IOException.class)
    public void handleIoException(IOException e, HttpServletRequest request) {
        // SSE 心跳或推送时客户端已断开，属于正常行为，不打 ERROR 日志
        String uri = request.getRequestURI();
        if (uri != null && uri.contains("/trace")) {
            log.debug("SSE 连接已断开: {} {}", request.getMethod(), uri);
        } else {
            log.warn("IO 异常: {} {} - {}", request.getMethod(), uri, e.getMessage());
        }
    }

    // ==================== 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        log.error("未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_INTERNAL_ERROR, "系统内部错误，请稍后重试"));
    }
}
