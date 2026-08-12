package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.common.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // ==================== 静态资源缺失（如浏览器自动请求 favicon.ico）====================

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    // ==================== 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        log.error("未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_INTERNAL_ERROR, "系统内部错误，请稍后重试"));
    }
}
