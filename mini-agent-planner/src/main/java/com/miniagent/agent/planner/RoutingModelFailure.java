package com.miniagent.agent.planner;

import dev.langchain4j.exception.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Safe routing diagnostics. Never expose provider response bodies or credentials. */
final class RoutingModelFailure {
    private RoutingModelFailure() {}

    static String reason(Throwable failure) {
        List<Throwable> causes = causes(failure);
        // The SDK wraps both 401 and 403 in AuthenticationException. Keep the HTTP distinction.
        for (Throwable cause : causes) {
            if (cause instanceof HttpException http) {
                String reason = switch (http.statusCode()) {
                    case 401 -> "ROUTER_MODEL_AUTHENTICATION_FAILED";
                    case 403 -> "ROUTER_MODEL_PERMISSION_DENIED";
                    case 404 -> "ROUTER_MODEL_NOT_FOUND";
                    case 408, 504 -> "ROUTER_MODEL_TIMEOUT";
                    case 429 -> "ROUTER_MODEL_RATE_LIMITED";
                    default -> http.statusCode() >= 500 && http.statusCode() < 600
                            ? "ROUTER_MODEL_SERVICE_UNAVAILABLE"
                            : http.statusCode() >= 400 && http.statusCode() < 500
                            ? "ROUTER_MODEL_INVALID_REQUEST" : null;
                };
                if (reason != null) return reason;
            }
        }
        for (Throwable cause : causes) {
            if (cause instanceof AuthenticationException) return "ROUTER_MODEL_AUTHENTICATION_FAILED";
            if (cause instanceof ModelNotFoundException) return "ROUTER_MODEL_NOT_FOUND";
            if (cause instanceof RateLimitException) return "ROUTER_MODEL_RATE_LIMITED";
            if (cause instanceof dev.langchain4j.exception.TimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException)
                return "ROUTER_MODEL_TIMEOUT";
            if (cause instanceof InvalidRequestException) return "ROUTER_MODEL_INVALID_REQUEST";
            if (cause instanceof InternalServerException) return "ROUTER_MODEL_SERVICE_UNAVAILABLE";
            if (cause instanceof UnresolvedModelServerException || cause instanceof java.io.IOException
                    || cause instanceof java.nio.channels.UnresolvedAddressException)
                return "ROUTER_MODEL_CONNECTION_FAILED";
        }
        return "ROUTER_MODEL_ERROR";
    }

    static String userMessage(String reason) {
        String detail = switch (reason == null ? "" : reason) {
            case "ROUTER_MODEL_AUTHENTICATION_FAILED" ->
                    "模型服务认证失败（401）。请在设置中核对服务地址、模型名称和该服务商的 API Key，并重新保存。";
            case "ROUTER_MODEL_PERMISSION_DENIED" ->
                    "模型服务拒绝访问（403）。请检查 API Key 的权限以及账号是否获准使用所选模型。";
            case "ROUTER_MODEL_NOT_FOUND" ->
                    "模型接口或模型不存在（404）。请在设置中核对兼容 OpenAI 的 Base URL 和模型名称。";
            case "ROUTER_MODEL_RATE_LIMITED" ->
                    "模型服务返回限流或额度不足（429）。请检查账号额度或稍后重试。";
            case "ROUTER_MODEL_TIMEOUT" ->
                    "模型服务响应超时。请检查服务地址和网络连接后重试。";
            case "ROUTER_MODEL_CONNECTION_FAILED" ->
                    "无法连接模型服务。请检查设置中的服务地址、网络连接及代理配置。";
            case "ROUTER_MODEL_SERVICE_UNAVAILABLE" ->
                    "模型服务暂时不可用（5xx）。请稍后重试或在设置中切换可用服务。";
            case "ROUTER_MODEL_INVALID_REQUEST" ->
                    "模型服务不接受当前请求。请核对模型名称及接口是否兼容 OpenAI Chat Completions。";
            case "ROUTER_MODEL_UNAVAILABLE" -> "当前没有可用的模型。请先在设置中配置模型服务。";
            case "ROUTER_INVALID_RESPONSE", "ROUTER_INVALID_ARTIFACT_EVIDENCE" ->
                    "模型返回的任务策略未通过格式校验。请重试，或切换支持结构化输出的模型。";
            default -> "本次任务的执行策略未生成成功。请检查模型设置后重试。";
        };
        return detail + "本次尚未开始操作，原有任务进度已保留。";
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> result = new ArrayList<>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && result.size() < 32 && seen.add(cause);
             cause = cause.getCause()) result.add(cause);
        return result;
    }
}
