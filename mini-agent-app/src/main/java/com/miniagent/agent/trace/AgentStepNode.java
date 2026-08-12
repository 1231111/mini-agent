package com.miniagent.agent.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体执行节点全集（库内 stepType 唯一真相源）。
 * {@link #isCore()}：核心业务轨迹；false=状态/只读/调试，默认 UI 可折叠隐藏。
 */
public enum AgentStepNode {

    RUN_START("生命周期", "任务开始", "收到用户问题，创建 executionId", true, true),
    AGENT_LOOP_START("生命周期", "进入主循环", "开始 Agent 多轮：模型→工具→再决策", true, true),
    /** 结束原因（SUCCESS/MAX_ITERATIONS/CANCELED/ABORTED 等）写在 status/content，勿另建 LOOP_END */
    AGENT_LOOP_END("生命周期", "离开主循环", "主 Agent 循环结束（与 START 成对；原因见 status）", true, true),
    SUBAGENT_LOOP_START("生命周期", "子代理启动", "delegate_task 拉起子 Agent；子步骤挂 parentStepId", true, true),
    SUBAGENT_LOOP_END("生命周期", "子代理结束", "子 Agent 结束，结果回父任务", true, true),
    COMPRESSION("生命周期", "上下文压缩", "历史过长，压缩后继续（有耗时）", true, true),
    /** ContextLoader 按意图加载本轮上下文后的审计快照 */
    CONTEXT_LOAD("上下文加载", "加载本轮上下文", "按意图裁剪后的历史/记忆/todo 注入说明", true, true),

    // —— 意图识别（独立功能阶段，先于执行计划）——
    INTENT_START("意图识别", "意图识别开始", "进入意图漏斗：L0规则 → L1小模型 → L2启发式", true, true),
    INTENT_L0("意图识别", "意图·L0规则", "规则层（YAML/MySQL）：命中则短路，未命中继续下层", true, true),
    INTENT_L1("意图识别", "意图·L1小模型", "小模型分类层：独立意图分类模型", true, true),
    INTENT_L2("意图识别", "意图·L2启发式", "启发式兜底层：规则与小模型未定案时", true, true),
    INTENT_END("意图识别", "意图识别结束", "漏斗定案完成，产出意图类型与任务目标", true, true),
    /** 意图识别的下游产物：供主循环执行用的计划快照 */
    TASK_PLAN("执行计划", "执行计划定案", "由意图识别产出的 TaskPlan（目标/白名单/是否强制拆任务）", true, true),
    /** @deprecated 用 INTENT 的 route 标签；不再落库 */
    REVIEW_PATH("意图识别", "截图点评路径(已废弃)", "请用 route=REVIEW_FAST_PATH 标签", false, false),

    // —— 生产级 Planner（Goal / DAG / Proposal / Recovery）——
    GOAL_COMPILED("规划控制", "目标编译完成", "NL+Intent → Goal + TaskGraph", true, true),
    GRAPH_UPDATED("规划控制", "任务图更新", "DAG 节点状态或结构变更", true, true),
    PROPOSAL("规划控制", "动作提案", "ActionProposal（basedOnVersion + actions）", true, true),
    STATE_COMMIT("规划控制", "状态提交", "PlannerStateStore CAS 升版本", true, true),
    RECOVERY_LOCAL("规划控制", "恢复·局部修复", "FailureClass=LOCAL_REPAIR", true, true),
    RECOVERY_REPLACE_TOOL("规划控制", "恢复·换工具", "FailureClass=REPLACE_TOOL", true, true),
    RECOVERY_REWRITE_GRAPH("规划控制", "恢复·改图", "FailureClass=REWRITE_GRAPH", true, true),
    RECOVERY_REVISE_GOAL("规划控制", "恢复·改目标", "FailureClass=REVISE_GOAL", true, true),

    TASK_SEED("任务拆分", "播种任务清单", "用意图计划预填子目标栈", true, true),
    TASK_SET("任务拆分", "建立任务清单", "task.set：写入完整拆分", true, true),
    TASK_UPDATE("任务拆分", "更新任务状态", "task.update：推进/完成/阻塞", true, true),
    TASK_LIST("任务拆分", "查询任务列表", "只读查询，不改变业务状态", true, false),
    TASK_REOPEN("任务拆分", "回滚重开", "task.reopen", true, true),
    TASK_CONFIRM("任务拆分", "人工确认", "task.confirm", true, true),
    TASK_CLEAR("任务拆分", "清空任务清单", "task.clear", true, true),

    WAITING_FOR_HUMAN("人机协作", "等待人工", "已抛出确认/审批请求，阻塞等待用户回复", true, true),
    HUMAN_CONFIRM_REJECTED("人机协作", "人工驳回", "用户拒绝确认，任务未按原计划继续", true, true),

    PLAN("各轮目标", "本轮目标", "进入第 N 轮；子目标指针附在本节点元数据", true, true),
    /** 状态指针，非动作；不落库。变化已并入 PLAN 元数据 */
    SUB_GOAL("各轮目标", "当前子目标(状态)", "框架子目标指针快照（勿混入核心步骤表）", false, false),

    THINKING("决策/反思", "模型思考", "中间思考；【LLM 请求/响应】为展示别名", true, true),
    DECISION("决策/反思", "决策说明", "选工具前的理由", true, true),

    LLM_CALL("模型调用", "调用耗时", "成功调用的时延与 token", true, true),
    LLM_CALL_ERROR("模型调用", "模型调用失败", "LLM 超时/异常", true, true),
    LLM_RETRY("模型调用", "模型自动重试", "失败后框架重试/裁剪上下文", true, true),
    LLM_REQUEST("模型调用", "请求发出", "展示别名，库内=THINKING", false, true),
    LLM_RESPONSE("模型调用", "响应返回", "展示别名，库内=THINKING", false, true),

    TOOL_CALL("工具调用", "调用发起", "发起工具调用", true, true),
    TOOL_RESULT("工具调用", "结果返回", "工具成功返回", true, true),
    TOOL_ERROR("工具调用", "工具执行异常", "超时/限流/业务错误等", true, true),
    TOOL_RETRY("工具调用", "工具自动重试", "失败后自动重试", true, true),
    PERM_DENY("工具调用", "权限拒绝", "Plan/Ask 闸门", true, true),
    HOOK_DENY("工具调用", "钩子拒绝", "ToolHook 拒绝", true, true),

    ANSWER("最终结果", "最终回答", "对用户可见产出", true, true),
    CANCELED("最终结果", "用户取消", "用户主动取消本次执行", true, true),
    ABORTED("最终结果", "异常终止", "超时/熔断/系统中断等非业务失败终止", true, true),
    ERROR("最终结果", "执行错误", "不可恢复异常", true, true),
    RUN_END("最终结果", "任务结束", "整次 execution 收尾", true, true);

    private static final Logger log = LoggerFactory.getLogger(AgentStepNode.class);

    private final String group;
    private final String label;
    private final String description;
    private final boolean persisted;
    private final boolean core;

    AgentStepNode(String group, String label, String description, boolean persisted, boolean core) {
        this.group = group;
        this.label = label;
        this.description = description;
        this.persisted = persisted;
        this.core = core;
    }

    public String getGroup() { return group; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public boolean isPersisted() { return persisted; }
    public boolean isCore() { return core; }

    public static Optional<AgentStepNode> ofCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String key = code.trim().toUpperCase();
        // 历史库兼容：旧 TODO_* / LOOP_END
        if (key.startsWith("TODO_")) {
            key = "TASK_" + key.substring(5);
        } else if ("LOOP_END".equals(key)) {
            key = AGENT_LOOP_END.name();
        }
        try {
            return Optional.of(valueOf(key));
        } catch (IllegalArgumentException e) {
            log.warn("未知 AgentStepNode code（将按非核心/非登记处理）: {}", code);
            return Optional.empty();
        }
    }

    /** 是否为已知且应落库的节点；未知 code → false（并已由 ofCode WARN） */
    public static boolean isKnownPersisted(String code) {
        return ofCode(code).map(AgentStepNode::isPersisted).orElse(false);
    }

    /** 未知 code → false（勿默认真，避免脏码被当核心） */
    public static boolean isCoreCode(String code) {
        return ofCode(code).map(AgentStepNode::isCore).orElse(false);
    }

    public static List<Map<String, Object>> catalog() {
        return Arrays.stream(values()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.name());
            m.put("group", t.group);
            m.put("stage", t.group);
            m.put("label", t.label);
            m.put("description", t.description);
            m.put("persisted", t.persisted);
            m.put("core", t.core);
            return m;
        }).toList();
    }
}
