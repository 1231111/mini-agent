package com.miniagent.memoryservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 进化闭环的全部阈值（前缀 {@code memory.evolution}）。默认值即设计文档 §7.9 的建议值；
 * 偏离处在字段注释里写明。租户级模式 / 冻结 / 预算覆盖存在 {@code evo_tenant_policy}。
 */
@Data
@ConfigurationProperties(prefix = "memory.evolution")
public class EvolutionProperties {
    private Policy policy = new Policy();
    private Signal signal = new Signal();
    private Evidence evidence = new Evidence();
    private Trigger trigger = new Trigger();
    private Recurrence recurrence = new Recurrence();
    private Proposal proposal = new Proposal();
    private Approval approval = new Approval();
    private Eval eval = new Eval();
    private Regression regression = new Regression();
    private Experiment experiment = new Experiment();
    private Monitor monitor = new Monitor();
    private Health health = new Health();
    private Revalidate revalidate = new Revalidate();
    private Optimize optimize = new Optimize();
    private Lifecycle lifecycle = new Lifecycle();
    private Meta meta = new Meta();
    private Holdout holdout = new Holdout();
    private Budget budget = new Budget();
    private Breaker breaker = new Breaker();
    private Jobs jobs = new Jobs();
    private Llm llm = new Llm();
    private Context context = new Context();
    private Retention retention = new Retention();
    private Gate gate = new Gate();

    @Data
    public static class Policy {
        /** 未配置租户策略时的模式：OFF / SHADOW / ASSISTED / AUTO。 */
        private String defaultMode = "AUTO";
    }

    @Data
    public static class Signal {
        /**
         * 不作为证据、也不计入实验成败的错误码前缀。App 回传的是工具错误码
         * （{@code ToolErrorCode} 名），所以默认值是基础设施类工具错误，而非文档里的 ErrorCode 前缀。
         */
        private List<String> excludedErrorPrefixes = List.of(
                "TIMEOUT", "RATE_LIMITED", "DEPENDENCY_UNAVAILABLE", "CANCELLED", "RESOURCE_BUSY");
    }

    @Data
    public static class Evidence {
        private int windowDays = 14;
        private int minClusterSize = 3;
    }

    @Data
    public static class Trigger {
        private int recentApplications = 30;
        private double minFailureRate = 0.3;
        private int minUserNegatives = 2;
        /** 失败事件到达后延迟多少秒再检测，合并同一批失败。 */
        private int debounceSeconds = 60;
    }

    @Data
    public static class Recurrence {
        private int windowDays = 14;
        private int minCount = 2;
    }

    @Data
    public static class Proposal {
        private int cooldownDays = 7;
        private int maxOpenPerTenant = 20;
    }

    @Data
    public static class Approval {
        private int expireDays = 14;
    }

    @Data
    public static class Eval {
        private int minSuccessCases = 10;
        private int maxPairwiseCases = 40;
        private int maxRegressionCases = 30;
        private double passFixRate = 0.6;
        private double maxRegressionRate = 0.1;
        private double maxRegressionSetFailureRate = 0.05;
        /** 判错（ERROR）占比超过该值视为评估无效。 */
        private double maxErrorRate = 0.3;
        private int agreementWindow = 20;
        private double minOfflineOnlineAgreement = 0.5;
        /** 评估作业租约：一次评估要回放全部案例（每案 2 次编译 + 2 次判定），远长于普通作业；本实现新增。 */
        private int leaseSeconds = 3600;
    }

    @Data
    public static class Regression {
        private int maxCasesPerItem = 200;
    }

    @Data
    public static class Experiment {
        private int minSamplesPerArm = 30;
        /**
         * true：离线通过后开在线对照，两边都有样本才晋升。
         * 客户端只跑本库里的当前修订，没有对照臂流量，所以部署默认关掉，离线通过即升级。
         */
        private boolean requireLiveArms = true;
        private double promoteProbability = 0.95;
        private double rejectProbability = 0.90;
        private double nonInferiorityMargin = 0.05;
        private int maxDays = 14;
        /** 万分比，必须是 100 的整数倍（App 侧按整数百分比回传）。 */
        private int userScopeBps = 5000;
        private List<Integer> projectRampBps = List.of(500, 2500, 5000);
        private int rampMinHours = 24;
        /**
         * 「流量足」：按近 traffic-window-days 天的任务数外推，max-days 天内候选臂（按最终流量）
         * 能拿到 min-samples-per-arm 个任务。优化扫描与重验排序也用这个窗口。
         */
        private int trafficWindowDays = 30;
        /** 文档：决策每小时一次。 */
        private int checkIntervalMinutes = 60;
    }

    /** 监控期护栏沿用 experiment 的非劣效边界与拒绝阈值；流量不足时与晋升前比较，用 health 阈值。 */
    @Data
    public static class Monitor {
        private int days = 7;
        private int controlBps = 1000;
        private int checkIntervalMinutes = 60;
        /** 无对照臂（流量不足直通晋升）时，新修订至少积累多少任务才与晋升前比较；本实现新增。 */
        private int minTasksForHistoryComparison = 5;
    }

    @Data
    public static class Health {
        private int recentDays = 14;
        private int baselineDays = 30;
        private int minRecentApplications = 20;
        private double margin = 0.05;
        private double degradeProbability = 0.9;
    }

    @Data
    public static class Revalidate {
        private int maxCasesPerItem = 20;
        private double maxDrop = 0.1;
        /** 一次环境登记最多重验的条目数（按最近使用排序）。 */
        private int maxItemsPerChange = 50;
    }

    @Data
    public static class Optimize {
        private double targetSuccessRate = 0.9;
        private int maxPerTenantPerWeek = 3;
    }

    @Data
    public static class Lifecycle {
        private int idleDays = 90;
        private int archiveMinApplications = 10;
        private double archiveMaxSuccessRate = 0.20;
    }

    @Data
    public static class Meta {
        private String generatorChampion = "minimal-edit-v1";
        /** 为空表示不启用挑战者。 */
        private String generatorChallenger = "";
        private double challengerShare = 0.2;
    }

    @Data
    public static class Holdout {
        private int bps = 500;
        private int periodDays = 90;
    }

    @Data
    public static class Budget {
        private long dailyTokensPerTenant = 2_000_000L;
    }

    @Data
    public static class Breaker {
        private int windowDays = 7;
        private int minPromotions = 10;
        private double rollbackRate = 0.3;
    }

    @Data
    public static class Jobs {
        private int leaseSeconds = 300;
        private int maxAttempts = 10;
        private int maxConcurrentEvalsPerTenant = 2;
        private int batchSize = 5;
        private int sweepIntervalHours = 24;
    }

    @Data
    public static class Llm {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int timeoutSeconds = 120;
    }

    @Data
    public static class Context {
        private int maxItems = 20;
    }

    @Data
    public static class Retention {
        private int outcomeDays = 180;
        private int doneJobDays = 7;
    }

    /** 候选静态闸门（文档 §7.2）。步骤上限取文档值；长度、改动比例与提示词条数是本实现新增的键。 */
    @Data
    public static class Gate {
        private int maxSteps = 12;
        private int maxAddedSteps = 3;
        private int maxListItems = 20;
        private int maxItemChars = 500;
        private int maxDescriptionChars = 2000;
        /** 步骤序列按 LCS 计算的改动比例上限（文档「diff 行数有上限」）。 */
        private double maxStepChangeRatio = 0.6;
        /** 防投毒：引用证据至少来自几个不同会话；PROJECT 作用域另要求几名不同成员。 */
        private int minEvidenceSessions = 2;
        private int minProjectMembers = 2;
        /** 提示词里最多放多少条失败证据 / 成功案例（文档：2–3 个成功案例摘要）。 */
        private int promptFailureCases = 8;
        private int promptSuccessCases = 3;
    }
}
