package com.miniagent.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Keep driver/server diagnostics in logs, rather than persisting a stack trace as an answer. */
public final class UserFacingFailure {
    private static final Logger LOG = LoggerFactory.getLogger(UserFacingFailure.class);

    private UserFacingFailure() { }

    public static String summarize(String detail, String fallback) {
        if (detail == null || detail.isBlank()) return fallback;
        if (!containsDiagnosticDump(detail)) return detail;
        String id = UUID.randomUUID().toString();
        LOG.warn("Agent failure diagnosticId={} detail={}", id, detail);
        return fallback + " 诊断编号：" + id + "，可在执行日志中查看详情。";
    }

    public static boolean containsDiagnosticDump(String detail) {
        if (detail == null) return false;
        return detail.contains("stack='") || detail.contains("stack=\"")
                || detail.contains("\n\tat ") || detail.contains("\n    at ")
                || detail.contains("\nat ") || detail.contains("Error {\n")
                || detail.contains("Error {\r") || detail.contains("playwright-java-")
                || detail.contains("ElementHandleDispatcher.");
    }
}
