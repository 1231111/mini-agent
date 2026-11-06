package com.miniagent.agent.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 模型回合结果。拒答/空答在适配器边界分类，不进用户答案通道。
 */
public record LlmTurn(Status status, ChatResponse response, String refuseKind) {

    public enum Status {
        TOOL_CALLS,
        CONTENT,
        REFUSED,
        EMPTY,
        ERROR
    }

    static final String REFUSE_VENDOR_ENVELOPE = "VENDOR_ENVELOPE";
    static final String REFUSE_NULL_RESPONSE = "null_response";

    /** 厂商安全网关信封，与 HTTP 403 同类，只在 classify 里映射。 */
    private static final List<String> VENDOR_REFUSE_MARKERS = List.of(
            "request was rejected",
            "considered high risk",
            "content_filter",
            "content filter"
    );

    public static LlmTurn classify(ChatResponse response) {
        if (response == null) {
            return new LlmTurn(Status.ERROR, null, REFUSE_NULL_RESPONSE);
        }
        FinishReason finishReason = response.finishReason();
        if (isSafetyFinish(finishReason)) {
            return new LlmTurn(Status.REFUSED, response, finishReason.name());
        }
        AiMessage ai = response.aiMessage();
        if (ai == null) {
            return new LlmTurn(Status.EMPTY, response, null);
        }
        if (ai.hasToolExecutionRequests()) {
            return new LlmTurn(Status.TOOL_CALLS, response, null);
        }
        String text = ai.text();
        if (StringUtils.isBlank(text)) {
            return new LlmTurn(Status.EMPTY, response, null);
        }
        if (isVendorEnvelope(text)) {
            return new LlmTurn(Status.REFUSED, response, REFUSE_VENDOR_ENVELOPE);
        }
        return new LlmTurn(Status.CONTENT, response, null);
    }

    static boolean isSafetyFinish(FinishReason finishReason) {
        if (finishReason == null) {
            return false;
        }
        String name = finishReason.name();
        return name.contains("CONTENT_FILTER") || name.contains("SAFETY");
    }

    static boolean isVendorEnvelope(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String marker : VENDOR_REFUSE_MARKERS) {
            if (t.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
