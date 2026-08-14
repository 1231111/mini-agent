package com.miniagent.agent.todo;

/**
 * 可插拔步骤验收器（双轨制第二轨的扩展点）。
 * 所有 Spring Bean 实现都会在 todo completed 时依次执行；任一失败则拒绝勾选。
 */
public interface TodoStepValidator {

    /** 校验器名称（日志/错误信息） */
    String name();

    /**
     * @return null 表示通过；非空为失败原因
     */
    String validate(TaskTodoStore.TodoItem item, String evidence);
}
