package com.miniagent.agent.intent;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图识别器 — 极简版 + 复杂任务强制结构化计划。
 */
@Slf4j
@Component
public class IntentPlanner {

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

    private static final List<String> IMAGE_TOOLS = List.of(
            "image_generate",
            "comfyui_status", "comfyui_workflows", "comfyui_models", "comfyui_execute",
            "comfyui_txt2img", "comfyui_img2img", "comfyui_check_quality", "comfyui_img2video",
            "todo", "memory", "delegate_task"
    );

    private static final Pattern IMAGE_INTENT = Pattern.compile(
            "(?i)("
                    + "生成.*(图|图片|插画|海报|封面|壁纸|截图)"
                    + "|画一?[张幅]|画个|画一张"
                    + "|文生图|图生图|txt2img|img2img"
                    + "|draw\\s+(a|an|me)|generate\\s+(an?\\s+)?image|create\\s+(an?\\s+)?image"
                    + "|make\\s+(an?\\s+)?(image|picture|illustration)"
                    + ")"
    );

    public TaskPlan plan(ChatModel chatModel, String userMessage, boolean hasImage) {
        String text = userMessage == null ? "" : userMessage.trim();

        if (hasImage && text.length() <= 10) {
            log.info("意图: REVIEW（图片反馈）");
            return new TaskPlan(IntentType.REVIEW, text.isBlank() ? "分析用户截图反馈" : text,
                    true, true, false, List.of(), List.of(), "图片反馈", false);
        }

        boolean complex = ComplexTaskDetector.isComplex(text);

        // 明确生图且非复杂工程任务 → 收窄工具面；多图/批量仍可强制计划
        if (!text.isBlank() && IMAGE_INTENT.matcher(text).find() && !complex) {
            log.info("意图: IMAGE_GENERATION（关键词快路径）");
            return new TaskPlan(IntentType.IMAGE_GENERATION, text, true, true, true,
                    IMAGE_TOOLS, List.of(), "图片生成关键词快路径", false);
        }

        if (complex) {
            log.info("意图: NEW_TASK + 强制结构化计划（复杂任务）");
            return new TaskPlan(IntentType.NEW_TASK, text, true, true, true,
                    ALL_TOOLS, List.of(), "复杂任务：强制 todo 计划与验收", true);
        }

        log.info("意图: NEW_TASK, hasImage={}", hasImage);
        return new TaskPlan(IntentType.NEW_TASK, text, true, true, true,
                ALL_TOOLS, List.of(), "默认执行", false);
    }
}
