package com.miniagent.agent.trace;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trace 节点全集（库内 stepType 唯一真相源）。
 * 新增节点必须先加枚举，再在业务里写入；未知码写入会打 warn。
 * <p>
 * 说明：LLM_REQUEST / LLM_RESPONSE 不是独立落库类型，是 THINKING.content 前缀的展示别名。
 */
public enum TraceStepType {

    // —— 生命周期 ——
    RUN_START("生命周期", "任务开始", "收到用户问题，创建 executionId", true),
    AGENT_LOOP_START("生命周期", "进入主循环", "开始 Agent 多轮：模型→工具→再决策", true),
    SUBAGENT_LOOP_START("生命周期", "子代理启动", "delegate_task 拉起子 Agent 循环", true),
    SUBAGENT_LOOP_END("生命周期", "子代理结束", "子 Agent 循环结束，结果回父任务", true),
    COMPRESSION("生命周期", "上下文压缩", "历史过长，压缩后继续", true),

    // —— 执行计划 ——
    INTENT_L0("执行计划", "L0·规则命中", "判定方式=规则（YAML/MySQL），高置信短路，未调小模型", true),
    INTENT_L1("执行计划", "L1·小模型分类", "判定方式=独立意图小模型，输出意图/目标/理由", true),
    INTENT_L2("执行计划", "L2·启发式兜底", "判定方式=代码启发式（L0未命中且L1未用/低置信）", true),
    TASK_PLAN("执行计划", "执行计划定案", "本轮最终采用的 TaskPlan（目标/白名单/是否强制 todo）", true),
    REVIEW_PATH("执行计划", "截图点评路径", "REVIEW 快路径，不进完整主循环", true),

    // —— Todo拆分 ——
    TODO_SEED("Todo拆分", "播种待办", "用意图计划预填 todo/子目标栈", true),
    TODO_SET("Todo拆分", "建立计划", "todo.set：写入完整拆分列表", true),
    TODO_UPDATE("Todo拆分", "更新状态", "todo.update：推进/完成/阻塞", true),
    TODO_LIST("Todo拆分", "查询列表", "todo.list：读取当前计划", true),
    TODO_REOPEN("Todo拆分", "回滚重开", "todo.reopen：回滚并级联下游", true),
    TODO_CONFIRM("Todo拆分", "人工确认", "todo.confirm：关键步放行", true),
    TODO_CLEAR("Todo拆分", "清空计划", "todo.clear：清空计划", true),

    // —— 各轮目标 ——
    PLAN("各轮目标", "本轮目标", "进入第 N 轮迭代（准备发 LLM）", true),
    SUB_GOAL("各轮目标", "当前子目标", "框架维护的当前子目标指针", true),

    // —— 决策/反思 ——
    THINKING("决策/反思", "模型思考", "中间思考；含【LLM 请求/响应】整包时 UI 会拆成展示别名", true),
    DECISION("决策/反思", "决策说明", "选工具前的简短理由", true),

    // —— 模型调用 ——
    LLM_CALL("模型调用", "调用耗时", "本轮 LLM 时延与 token 用量", true),
    /** 展示别名：content 以「【LLM 请求】」开头的 THINKING */
    LLM_REQUEST("模型调用", "请求发出", "展示别名，库内 stepType=THINKING", false),
    /** 展示别名：content 以「【LLM 响应】」开头的 THINKING */
    LLM_RESPONSE("模型调用", "响应返回", "展示别名，库内 stepType=THINKING", false),

    // —— 工具调用 ——
    TOOL_CALL("工具调用", "调用发起", "发起工具调用（参数在 toolArgs）", true),
    TOOL_RESULT("工具调用", "结果返回", "工具执行返回", true),
    PERM_DENY("工具调用", "权限拒绝", "Plan/Ask 权限闸门拦住", true),
    HOOK_DENY("工具调用", "钩子拒绝", "ToolHook 拒绝该调用", true),

    // —— 最终结果 ——
    ANSWER("最终结果", "最终回答", "对用户的最终文本/产物", true),
    LOOP_END("最终结果", "循环结束", "Agent 循环结束原因（SUCCESS/MAX_ITERATIONS 等）", true),
    ERROR("最终结果", "执行错误", "本轮执行异常", true),
    RUN_END("最终结果", "任务结束", "整次 execution 收尾", true);

    private final String stage;
    private final String label;
    private final String description;
    /** true=会写入 agent_trace_step.stepType；false=仅 UI 展示别名 */
    private final boolean persisted;

    TraceStepType(String stage, String label, String description, boolean persisted) {
        this.stage = stage;
        this.label = label;
        this.description = description;
        this.persisted = persisted;
    }

    public String getStage() { return stage; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public boolean isPersisted() { return persisted; }

    public static Optional<TraceStepType> ofCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isKnownPersisted(String code) {
        return ofCode(code).map(TraceStepType::isPersisted).orElse(false);
    }

    /** 页面/API 用的完整目录 */
    public static List<Map<String, Object>> catalog() {
        return Arrays.stream(values()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.name());
            m.put("stage", t.stage);
            m.put("label", t.label);
            m.put("description", t.description);
            m.put("persisted", t.persisted);
            return m;
        }).toList();
    }
}
