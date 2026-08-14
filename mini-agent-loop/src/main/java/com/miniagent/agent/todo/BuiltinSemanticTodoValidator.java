package com.miniagent.agent.todo;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Objects;

/**
 * 默认语义验收：非空文件、需图任务含 markdown 图片、SQL 含 DDL 等。
 */
@Component
@Order(100)
public class BuiltinSemanticTodoValidator implements TodoStepValidator {

    @Override
    public String name() {
        return "builtin_semantic";
    }

    @Override
    public String validate(TaskTodoStore.TodoItem item, String evidence) {
        if (Objects.isNull(item)) return "校验项为空";
        String dw = Objects.isNull(item.doneWhen()) ? "" : item.doneWhen().trim();
        // llm_judge 交给专用校验器
        if (dw.regionMatches(true, 0, "llm_judge:", 0, "llm_judge:".length())) {
            return null;
        }
        TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                item.content(), item.doneWhen(), evidence);
        return r.ok() ? null : r.error();
    }

    /** 供 Store 写入 validation_hash */
    public static String hashOf(TaskTodoStore.TodoItem item, String evidence) {
        TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                item.content(), item.doneWhen(), evidence);
        return r.ok() ? r.contentHash() : "";
    }
}
