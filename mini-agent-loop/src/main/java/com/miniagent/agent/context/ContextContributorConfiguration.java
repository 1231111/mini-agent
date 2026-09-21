package com.miniagent.agent.context;

import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.application.PromptTemplates;
import com.miniagent.memory.MemoryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 现有 system prompt 块拆成贡献者。顺序即拼装顺序。
 */
@Configuration
@EnableConfigurationProperties(ContextBudgetProperties.class)
public class ContextContributorConfiguration {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");

    @Bean
    @Order(10)
    SystemContextContributor identityContributor() {
        return ctx -> ContextFragment.of(ContextSlot.IDENTITY, PromptTemplates.identity());
    }

    @Bean
    @Order(20)
    SystemContextContributor authorityContributor() {
        return ctx -> ContextFragment.of(ContextSlot.AUTHORITY, PromptTemplates.AUTHORITY);
    }

    /**
     * 只往 {@link ContextSlot#QUESTION} 里塞文字，不动任何资源。
     *
     * <p>点评轮（带媒体 + 无动手信号）优先于纯问答轮：两句判据都是可复核的事实，
     * 而「别继续发布/搜索、只看截图说话」这段约束在带媒体的那一轮更具体。
     * 工具清单、历史条数、执行路径一概不收窄 —— 那些收窄了退不回来。</p>
     */
    @Bean
    @Order(25)
    SystemContextContributor questionModeContributor() {
        return ctx -> {
            if (ctx.reviewTurn()) {
                return ContextFragment.of(ContextSlot.QUESTION, PromptTemplates.REVIEW_MODE_PROMPT);
            }
            if (!ctx.lightTurn()) {
                return ContextFragment.of(ContextSlot.QUESTION, "");
            }
            return ContextFragment.of(ContextSlot.QUESTION, PromptTemplates.QUESTION_MODE);
        };
    }

    @Bean
    @Order(30)
    SystemContextContributor referenceContributor() {
        return ctx -> {
            ContextReferenceDecision ref = ctx.reference();
            if (ref == null || !ref.shouldLoadPriorHistory()) {
                return ContextFragment.of(ContextSlot.REFERENCE, "");
            }
            return ContextFragment.of(ContextSlot.REFERENCE,
                    "以下消息中可能夹带按相关度检索到的历史片段，仅供指代消解参考；"
                            + "若与当前问题无关请忽略，勿编造未出现的报告/数据。");
        };
    }

    @Bean
    @Order(40)
    SystemContextContributor memoryContributor(MemoryService memoryService) {
        return ctx -> {
            String text = memoryService.retrieveForPrompt(
                    ctx.sessionId(), ctx.query(), ctx.policy().memoryPolicy());
            return ContextFragment.of(ContextSlot.MEMORY, text);
        };
    }

    @Bean
    @Order(50)
    SystemContextContributor skillsContributor(SkillStore skillStore) {
        return ctx -> {
            if (!ctx.policy().injectSkills()) {
                return ContextFragment.of(ContextSlot.SKILLS, "");
            }
            return ContextFragment.of(ContextSlot.SKILLS, skillStore.getSkillListSummary());
        };
    }

    @Bean
    @Order(60)
    SystemContextContributor reasoningContributor() {
        return ctx -> ContextFragment.of(ContextSlot.REASONING,
                PromptTemplates.REASONING + "\n\n" + PromptTemplates.COMPLETION);
    }

    @Bean
    @Order(70)
    SystemContextContributor toolsContributor() {
        return ctx -> ContextFragment.of(ContextSlot.TOOLS, "");
    }

    @Bean
    @Order(80)
    SystemContextContributor todoContributor(TaskTodoStore taskTodoStore) {
        return ctx -> {
            if (!ctx.policy().injectTodo() || StringUtils.isBlank(ctx.sessionId())) {
                return ContextFragment.of(ContextSlot.TODO, "");
            }
            return ContextFragment.of(ContextSlot.TODO, taskTodoStore.render(ctx.sessionId()));
        };
    }

    @Bean
    @Order(90)
    SystemContextContributor closingContributor() {
        return ctx -> {
            String now = LocalDateTime.now().format(CLOCK);
            return ContextFragment.of(ContextSlot.CLOSING,
                    PromptTemplates.CONFIRMATION + "\n\n"
                            + PromptTemplates.OUTPUT + "\n\n当前时间：" + now);
        };
    }

    public static String toolGuidance(Set<String> toolNames) {
        return toolGuidance(toolNames, false);
    }

    public static String toolGuidance(Set<String> toolNames, boolean graphOwned) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "本轮未开放工具调用，直接用中文回答。";
        }
        List<String> sorted = new ArrayList<>(toolNames);
        sorted.sort(String::compareTo);
        List<String> parts = new ArrayList<>();
        parts.add("本轮可调用工具：" + String.join(", ", sorted)
                + "。由你判断是否调用、调用哪一个；不要编造列表外的工具。");
        if (toolNames.contains("read_file")) {
            parts.add(PromptTemplates.FILE_GUIDANCE);
        }
        if (toolNames.contains("search_code") || toolNames.contains("ast_search")
                || toolNames.contains("codebase_search")) {
            parts.add(PromptTemplates.CODE_TOOLS_GUIDANCE);
        }
        if (toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.REASONING_STRATEGY);
        }
        parts.add(PromptTemplates.BEHAVIOR);
        if (toolNames.contains("browser_navigate")) {
            parts.add(PromptTemplates.BROWSER_GUIDANCE);
        }
        if (toolNames.contains("comfyui_status")) {
            parts.add(PromptTemplates.COMFYUI_GUIDANCE);
        }
        if (!graphOwned && (toolNames.contains("todo") || toolNames.contains("delegate_task"))) {
            parts.add(PromptTemplates.PLANNING_GUIDANCE);
        }
        if (toolNames.contains("write_file") && toolNames.contains("exec_command")) {
            parts.add(PromptTemplates.VERIFICATION_GUIDANCE);
        }
        return String.join("\n\n", parts);
    }
}
