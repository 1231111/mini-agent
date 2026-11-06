package com.miniagent.memoryservice.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * 裁判模型。成对比较正反两种顺序各问一次，两次结论一致才算胜负，否则记 TIE，抵消位置偏好；
 * 单方判定（回归案例、重验）只问「能否完成目标且不重现记录的失败」。解析不了的回答记 ERROR。
 */
@Component
public class PlanJudge {
    static final String PAIRWISE_SYSTEM = """
            你是任务计划评审。给你用户消息、这类任务过去失败的原因（可能为空）、
            以及同一个规划器在两版 SOP 下各自生成的计划 A 和计划 B。
            判断哪份计划更可能完成用户目标，同时避免重现记录的失败原因。
            只看计划内容；不要因为篇幅、措辞或先后顺序偏向任何一方；差不多就判 TIE。
            只输出 JSON：{"winner":"A|B|TIE","reason":"一句话"}
            """;
    static final String SINGLE_SYSTEM = """
            你是任务计划评审。给你用户消息、这类任务过去失败的原因（可能为空）和一份计划。
            判断这份计划能否完成用户目标，且不会重现记录的失败原因。
            只输出 JSON：{"pass":true|false,"reason":"一句话"}
            """;
    private static final String A = "A";
    private static final String B = "B";
    private static final String TIE = "TIE";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 以候选为主语：WIN = 候选计划更好。 */
    public Verdict pairwise(ChatModel judge, EvalCase c, String baselinePlan,
                            String candidatePlan) {
        String first = winner(judge, c, baselinePlan, candidatePlan);
        String second = winner(judge, c, candidatePlan, baselinePlan);
        if (first == null || second == null) {
            return Verdict.ERROR;
        }
        Verdict asListed = B.equals(first) ? Verdict.WIN : A.equals(first) ? Verdict.LOSS
                : Verdict.TIE;
        Verdict swapped = A.equals(second) ? Verdict.WIN : B.equals(second) ? Verdict.LOSS
                : Verdict.TIE;
        return asListed == swapped ? asListed : Verdict.TIE;
    }

    public Verdict single(ChatModel judge, EvalCase c, String plan) {
        JsonNode answer = ask(judge, SINGLE_SYSTEM, header(c) + "\n计划：\n" + plan);
        if (answer == null || !answer.path("pass").isBoolean()) {
            return Verdict.ERROR;
        }
        return answer.get("pass").asBoolean() ? Verdict.WIN : Verdict.LOSS;
    }

    private String winner(ChatModel judge, EvalCase c, String planA, String planB) {
        JsonNode answer = ask(judge, PAIRWISE_SYSTEM, header(c) + "\n计划 A：\n" + planA
                + "\n\n计划 B：\n" + planB);
        String winner = answer == null ? "" : answer.path("winner").asText("")
                .trim().toUpperCase(Locale.ROOT);
        return A.equals(winner) || B.equals(winner) || TIE.equals(winner) ? winner : null;
    }

    private static String header(EvalCase c) {
        return "用户消息：" + c.userMessage() + "\n过去失败的原因："
                + (c.failureReason() == null || c.failureReason().isBlank() ? "（无）"
                : c.failureReason());
    }

    private static JsonNode ask(ChatModel judge, String system, String user) {
        String text = judge.chat(ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from(user)).build())
                .aiMessage().text();
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (IOException e) {
            return null;
        }
    }
}
