package com.miniagent.agent.planner;

/** 失败诊断分类（Recovery 入口）。 */
public enum FailureClass {
    LOCAL_REPAIR,
    REPLACE_TOOL,
    REWRITE_GRAPH,
    REVISE_GOAL
}
