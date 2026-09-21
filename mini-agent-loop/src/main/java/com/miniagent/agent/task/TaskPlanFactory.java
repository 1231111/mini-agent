package com.miniagent.agent.task;

import com.miniagent.common.MessageConstants;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把用户消息变成 {@link TaskPlan}。
 *
 * <p>它只做两件事：提取事实信号、按信号算出「要不要走结构化计划」。
 * 不做分类、不算置信度、不选工具面。工具面由权限门收口，任务图由规划器按信号自己决定。</p>
 *
 * <p>替换掉的是原来的三层漏斗（配置规则 → 独立小模型 → 代码启发式）。
 * 那三层的作用是「在三种判断方式里选一个赢家，产出一个类别」；这个类不再产出类别，
 * 只把消息里已经出现的事实摊开。</p>
 */
@Component
public class TaskPlanFactory {

    private final TaskSignalMatcher matcher;
    private final TaskSignalProperties props;

    public TaskPlanFactory(TaskSignalMatcher matcher, TaskSignalProperties props) {
        this.matcher = matcher;
        this.props = props;
    }

    /**
     * 组装本轮任务参数。
     *
     * @param userMessage 用户消息原文；为 {@code null} 时按空消息处理
     */
    public TaskPlan build(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        // 系统控制语不是用户任务。前端「已批准「X」，请继续执行。」这类文本由代码拼出来、
        // 当用户消息发回来，里面必然带着工具名（write_file 等），照常提取信号会读成
        // 「本轮要落盘写文件」。控制语只说明上一步被放行，不构成任何新交付目标，
        // 所以这里不给它任何信号，也不允许它拉起任务图。
        if (MessageConstants.isSystemControlMessage(text)) {
            return new TaskPlan(text, null, List.of(),
                    "system-control:" + TaskSignals.NONE.describe(),
                    false, TaskSignals.NONE);
        }
        TaskSignals signals = matcher.of(text);
        return new TaskPlan(
                text,
                null,
                List.of(),
                "signals:" + signals.describe(),
                needsStructuredPlan(signals),
                signals);
    }

    /**
     * 要不要走结构化计划（规划器 + 任务图）。
     *
     * <p>判据全部来自消息文本事实，不看任何分类结果：</p>
     * <ul>
     *   <li>命中了复杂度词表，或点名要架构图/流程图——复杂产出要拆节点</li>
     *   <li>既要联网又要落盘——必然是两个能力阶段</li>
     *   <li>要落盘但不是单个文件的短指令——产出物不止一个或有中间产物</li>
     *   <li>图要写进文档，且开着这条开关</li>
     * </ul>
     *
     * <p>反过来说，单文件短指令、纯问答、纯生图都不走结构化计划，
     * 与删掉三层分类之前的判定保持一致。</p>
     */
    public boolean needsStructuredPlan(TaskSignals signals) {
        if (signals == null) {
            return false;
        }
        if (signals.complex() || signals.diagram()) {
            return true;
        }
        if (signals.needsWeb() && signals.needsFiles()) {
            return true;
        }
        if (signals.needsFiles() && !signals.simpleFile()) {
            return true;
        }
        return signals.imageIntoDoc() && props.getRules().isForceFullOnImageIntoDoc();
    }
}
