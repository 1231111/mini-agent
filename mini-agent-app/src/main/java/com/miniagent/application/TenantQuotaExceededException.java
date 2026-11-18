package com.miniagent.application;

/** Raised before a stream is opened when the tenant has already exhausted today's budget. */
public final class TenantQuotaExceededException extends RuntimeException {
    public TenantQuotaExceededException() {
        super("租户每日模型用量已达上限，请在配额重置后重试");
    }
}
