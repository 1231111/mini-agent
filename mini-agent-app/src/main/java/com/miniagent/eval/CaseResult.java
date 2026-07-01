package com.miniagent.eval;

import java.util.List;

/** 单个用例的运行结果（含每条断言明细），随报告落盘。 */
public class CaseResult {
    public String id;
    public String category;
    public boolean pass;
    public long elapsedMs;
    public String runtimeError;
    public List<CheckResult> checkResults;
}
