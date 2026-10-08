package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler：只选 READY，按 priority 出提案。
 * 参数齐的写/读锁定主工具；其余只带 capability。
 */
@Component
public class GraphScheduler {

    private final ReadyTaskSelector readySelector = new ReadyTaskSelector();
    private final PlannerProperties properties;

    public GraphScheduler() {
        this(new PlannerProperties());
    }

    @Autowired
    public GraphScheduler(PlannerProperties properties) {
        this.properties = properties == null ? new PlannerProperties() : properties;
    }

    public List<TaskNode> select(TaskGraph graph) {
        return readySelector.select(graph);
    }

    public ActionProposal propose(StateSnapshot snap, List<TaskNode> ready, int batchSize) {
        int n = Math.max(1, batchSize);
        List<ActionSpec> actions = new ArrayList<>();
        int taken = 0;
        String session = snap == null || snap.sessionId() == null ? "" : snap.sessionId();
        int timeout = properties.getActionTimeoutSeconds();
        for (TaskNode node : ready) {
            if (taken >= n) {
                break;
            }
            String cap = node.capability() == null ? "" : node.capability();
            String actionId = "act_" + UUID.randomUUID().toString().substring(0, 8);
            String concKey = session + ":" + node.id();
            ActionBinder.Bound bound = ActionBinder.bind(node, snap.graph());
            actions.add(new ActionSpec(
                    actionId,
                    node.id(),
                    bound.tool(), cap, bound.arguments(), node.doneWhen(),
                    node.compensation(),
                    idempotencyKey(snap, node, bound), timeout,
                    ActionRetryPolicy.forCapability(cap), concKey));
            taken++;
        }
        return new ActionProposal(
                "prop_" + UUID.randomUUID().toString().substring(0, 8),
                snap.version(),
                snap.planVersion(),
                snap.executionId(),
                actions);
    }

    /**
     * 幂等键 = 计划版本 + 节点 + **绑定参数摘要**。
     *
     * <p>为什么必须带参数摘要：动作日志（{@code ActionJournalKey}）用它判"这个动作做过没有"，
     * 而 {@code ToolPipeline} 命中 {@code SUCCEEDED} 时直接返回
     * {@code {"success":true,"deduplicated":true}} —— 不会再执行。</p>
     *
     * <p>此前键里只有 {@code planVersion + nodeId}，于是"上一步参数写错但被记为成功 → 用改正的
     * 参数重试"会命中同一条日志、被判成重复动作而**静默跳过**，节点还被标成成功，
     * 证据就是那句 dedup 文本 —— 表面全绿、实际没做。加上参数摘要后：参数变则键变、真的重试；
     * 参数不变则键不变，崩溃恢复时的去重语义保持不变（这正是恢复流程要的）。</p>
     */
    static String idempotencyKey(StateSnapshot snap, TaskNode node, ActionBinder.Bound bound) {
        return "idem-" + snap.planVersion() + "-" + node.id() + "-" + argumentsDigest(bound);
    }

    /** 参数摘要：按 key 排序序列化后再哈希，避免 Map 迭代序不同导致同一参数算出不同键。 */
    private static String argumentsDigest(ActionBinder.Bound bound) {
        if (bound == null || bound.arguments() == null || bound.arguments().isEmpty()) {
            return "noargs";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] json = mapper.writeValueAsBytes(bound.arguments());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            // 摘要算不出来时退回"不区分参数"，但这会让改正后的重试再次被去重跳过，
            // 所以宁可让键里带上随机串：那会退化成"可能重复执行"，而不是"静默不执行"。
            return "undigestible";
        }
    }
}
