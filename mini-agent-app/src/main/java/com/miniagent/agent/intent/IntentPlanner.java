package com.miniagent.agent.intent;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图识别器 — 极简版（hermes-agent 风格）。
 *
 * 设计哲学：
 *   不靠正则/LLM 预拆步骤去猜用户意图，信任 LLM 在 AgentLoop 里自主决定用什么工具、
 *   怎么拆解、何时收尾。复杂任务的拆解交给模型用 todo 工具自管理。
 *
 *   这里只保留一个判断：是否是 REVIEW（截图反馈/结果追问）。
 *   REVIEW 走特殊评审模式，不进 AgentLoop。其他全部 → NEW_TASK，带全历史进 AgentLoop。
 *
 * 之前的问题：planSteps 在循环外额外跑一次 LLM 把任务拆成僵硬的 step 列表，
 *   驱动 AgentLoop 的 step 计数器强制推进，反而切碎了模型自己的推理循环。已移除。
 */
@Slf4j
@Component
public class IntentPlanner {

    /**
     * 全部可用工具（BuiltinTools + TodoTool + DelegateTaskTool 自注册）。
     * 新增工具时需要同步更新此列表。
     */
    private static final List<String> ALL_TOOLS = List.of(
            "read_file", "list_files", "read_package", "write_file", "exec_command",
            "search_code", "edit_file", "ast_search", "codebase_search",
            "web_search", "web_extract", "http_get",
            "browser_navigate", "browser_snapshot", "browser_click",
            "browser_type", "browser_press", "browser_scroll",
            "browser_screenshot", "browser_evaluate", "browser_close",
            "memory", "skill_list", "skill_view", "skill_manage",
            "todo", "delegate_task",
            "image_generate",
            "comfyui_status", "comfyui_workflows", "comfyui_models", "comfyui_execute",
            "comfyui_txt2img", "comfyui_img2img", "comfyui_check_quality", "comfyui_img2video", "comfyui_tts"
    );

    /**
     * 意图识别：
     *   - 有图片 + 文字很短（<=10字）→ REVIEW（截图反馈），走评审模式
     *   - 其他 → NEW_TASK，全工具放进 AgentLoop，模型自主规划/续接（带全历史）
     *
     * 注意：chatModel 参数保留以兼容调用方签名，本实现不再用它做额外 LLM 调用。
     */
    public TaskPlan plan(ChatModel chatModel, String userMessage, boolean hasImage) {
        String text = userMessage == null ? "" : userMessage.trim();

        // 有图片且文字很短 → 视为截图反馈，走评审模式
        if (hasImage && text.length() <= 10) {
            log.info("意图: REVIEW（图片反馈）");
            return new TaskPlan(IntentType.REVIEW, text.isBlank() ? "分析用户截图反馈" : text,
                    true, true, false, List.of(), List.of(), "图片反馈");
        }

        log.info("意图: NEW_TASK, hasImage={}（模型在 AgentLoop 内自主规划，不预拆步骤）", hasImage);
        return new TaskPlan(IntentType.NEW_TASK, text, true, true, true,
                ALL_TOOLS, List.of(), "默认执行");
    }
}
