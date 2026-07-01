package com.miniagent.eval;

import java.util.Map;

/** 评估汇总：总通过率 + 分维度通过率。 */
public class EvalReport {
    public int totalCases;
    public int passedCases;
    public Map<String, Dimension> byCategory;

    /** 单个维度（tool/ctx/sec/...）的通过统计。 */
    public static class Dimension {
        public int total;
        public int passed;
    }
}
