package com.miniagent.account.web;

/** Shared atomic login-attempt admission contract. A positive return value is Retry-After seconds. */
@FunctionalInterface
public interface LoginAttemptRateLimiter {
    long acquire(String normalizedAccount, String sourceIp);
}
