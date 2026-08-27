package com.miniagent.eval;

import java.util.List;

/**
 * 一条评估用例（从 eval-cases/*.json 反序列化）。
 *
 * 设计原则：只断言「可观测的副作用」——模型回复文本、workspace 落盘文件、是否报错，
 * 不窥探 Agent 内部状态。这样用例与实现解耦，换模型/改循环都能复跑。
 *
 * 字段：
 *   id        唯一标识，如 bm_tool_01
 *   category  维度：tool/ctx/sec/func/intent/perf/rob/reason（用于分维度汇总）
 *   prompt    发给 Agent 的用户消息
 *   timeoutMs 单用例超时（毫秒），0 用全局默认
 *   checks    通过条件列表，全部通过该用例才算 PASS
 */
public class EvalCase {
    public String id;
    public String category;
    public String prompt;
    public long timeoutMs;
    public List<EvalCheck> checks;
}
