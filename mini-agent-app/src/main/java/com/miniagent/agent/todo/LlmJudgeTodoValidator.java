package com.miniagent.agent.todo;

import org.springframework.beans.factory.annotation.Autowired;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * 可选语义裁判：done_when = llm_judge:&lt;评判标准&gt;
 * evidence 为待评判文本，或指向文件的路径。
 * 模型须返回首行 PASS 或 FAIL。
 */
@Slf4j
@Component
@Order(200)
public class LlmJudgeTodoValidator implements TodoStepValidator {

    @Autowired
    private ChatModel chatModel;

    @Override
    public String name() {
        return "llm_judge";
    }

    @Override
    public String validate(TaskTodoStore.TodoItem item, String evidence) {
        if (Objects.isNull(item)) return "校验项为空";
        String dw = Objects.isNull(item.doneWhen()) ? "" : item.doneWhen().trim();
        if (!dw.regionMatches(true, 0, "llm_judge:", 0, "llm_judge:".length())) {
            return null; // 非本校验器职责
        }
        String criteria = dw.substring("llm_judge:".length()).trim();
        if (criteria.isEmpty()) {
            return "llm_judge 缺少评判标准，例：llm_judge:文档是否描述了四层电池结构";
        }
        String payload = loadEvidencePayload(evidence);
        if (StringUtils.isBlank(payload)) {
            return "llm_judge 无法读取 evidence 内容";
        }
        if (payload.length() > 12000) {
            payload = payload.substring(0, 12000) + "\n…(截断)";
        }

        String prompt = """
                你是严格的任务验收裁判。根据「评判标准」判断「待验收内容」是否达标。
                只输出两行：
                第一行：PASS 或 FAIL（大写，不得有其它文字）
                第二行：一句中文理由

                【评判标准】
                %s

                【子任务目标】
                %s

                【待验收内容】
                %s
                """.formatted(criteria, Objects.isNull(item.content()) ? "" : item.content(), payload);

        try {
            ChatResponse resp = chatModel.chat(UserMessage.from(prompt));
            String text = Objects.isNull(resp) || Objects.isNull(resp.aiMessage()) || Objects.isNull(resp.aiMessage().text())
                    ? "" : resp.aiMessage().text().trim();
            log.info("llm_judge 原始回复: {}", text.length() > 200 ? text.substring(0, 200) + "…" : text);
            String first = text.lines().findFirst().orElse("").trim().toUpperCase();
            if (first.startsWith("PASS")) {
                return null;
            }
            String reason = text.lines().skip(1).findFirst().orElse(text).trim();
            if (StringUtils.isBlank(reason)) reason = "未通过语义评判";
            return "LLM 语义验收 FAIL：" + reason;
        } catch (Exception e) {
            log.warn("llm_judge 调用失败: {}", e.getMessage());
            return "llm_judge 调用失败: " + e.getMessage();
        }
    }

    private static String loadEvidencePayload(String evidence) {
        if (StringUtils.isBlank(evidence)) return null;
        String ev = evidence.trim();
        try {
            Path p = Path.of(ev.replace('\\', '/'));
            if (!p.isAbsolute()) {
                String rel = com.miniagent.agent.tool.BuiltinTools.stripWorkspaceAlias(
                        ev.replace('\\', '/'));
                p = rel.isBlank()
                        ? com.miniagent.agent.tool.BuiltinTools.effectiveWorkspaceRoot()
                        : com.miniagent.agent.tool.BuiltinTools.effectiveWorkspaceRoot()
                                .resolve(rel).normalize();
            }
            if (Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 当作纯文本 evidence
        }
        return ev;
    }
}
