package com.miniagent.config.cloud;

import com.miniagent.common.ErrorCode;

/**
 * 云端账号服务调用失败。
 *
 * <p>带 {@link ErrorCode} 是刻意的：上层（{@code MiniAgentChatPageController}）要能把它
 * 原样透传给前端，让前端区分「密码错」和「云连不上」。若这里只抛一句 message，
 * 上面就只能包成 {@code AUTH_LOGIN_FAILED}，于是断网会被显示成「用户名或密码错误」——
 * 用户会反复改密码，而真正的问题是网络。
 */
public class CloudAccountException extends RuntimeException {

    private final ErrorCode errorCode;

    public CloudAccountException(ErrorCode errorCode, String detail) {
        super(pickMessage(errorCode, detail));
        this.errorCode = errorCode;
    }

    public CloudAccountException(ErrorCode errorCode, String detail, Throwable cause) {
        super(pickMessage(errorCode, detail), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * detail 为空时退回错误码自带的文案。注意不能用 {@code String.valueOf(detail) == null}
     * 来判空 —— 它对 null 返回字符串 "null"，永远不为 null；而 {@code detail.isBlank()}
     * 在 detail 为 null 时直接 NPE。两个写法都错得很难看出来。
     */
    private static String pickMessage(ErrorCode errorCode, String detail) {
        return (detail == null || detail.isBlank()) ? errorCode.getMessage() : detail;
    }
}
