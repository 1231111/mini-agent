package com.miniagent.eval;

/** 单条断言结果。 */
public class CheckResult {
    public boolean pass;
    public String description;
    public String reason;

    public static CheckResult pass(String desc) {
        CheckResult r = new CheckResult();
        r.pass = true; r.description = desc; return r;
    }
    public static CheckResult fail(String desc, String reason) {
        CheckResult r = new CheckResult();
        r.pass = false; r.description = desc; r.reason = reason; return r;
    }
}
