package com.miniagent.common;

/**
 * 统一消息常量，替换散落在各 Service 中的硬编码中文字符串。
 * <p>
 * 分类原则：
 * <ul>
 *   <li>用户可见的错误/提示/状态消息 → 放在这里</li>
 *   <li>LLM 系统提示词（PromptTemplates 等）→ 保持原样，不在此管理</li>
 *   <li>日志消息 → 保持原样，不在此管理</li>
 * </ul>
 */
public final class MessageConstants {

    private MessageConstants() {}

    // ==================== CHAT 对话 ====================
    public static final String CHAT_INPUT_EMPTY = "请输入有效内容。";
    public static final String CHAT_MULTIMODAL_EMPTY = "请输入文字或上传图片/音频/视频。";
    public static final String CHAT_IMAGE_PLACEHOLDER = "[图片]";
    public static final String CHAT_MEDIA_PLACEHOLDER = "[多模态附件]";
    public static final String CHAT_IMAGE_ANALYSIS = "分析图片";
    public static final String CHAT_DEFAULT_SESSION = "default";
    public static final String CHAT_USER_UPLOADED_IMAGES = "\n\n[用户上传了 %d 张图片]\n";
    public static final String CHAT_USER_UPLOADED_MEDIA = "\n\n[用户上传了音视频附件]\n";
    public static final String CHAT_IMAGE_LOCAL_PATH = "图片%d 本地路径: %s";
    public static final String CHAT_MEDIA_LOCAL_PATH = "%s 本地路径: %s";
    public static final String CHAT_TRUNCATED = "\n...（已截断）";
    public static final String CHAT_PERSIST_FAILED = "持久化会话历史失败: sessionId={}, err={}";
    public static final String CHAT_PROCESSING_ERROR = "处理出错: ";

    // ==================== AGENT 循环 ====================

    /**
     * 系统自己拼进会话的控制语前缀（批准 Plan / 批准工具等）。
     *
     * <p>这类文本被当成「用户消息」送进主循环，但它不是用户打的字。必须能与原话区分开，
     * 否则「已批准「write_file」，请继续执行。」会被任务信号提取读成一整条新任务 ——
     * 它命中 {@code file-signals} 里的 {@code write_file}，于是
     * {@code needsFiles=true} 而消息里一个真实文件名都没有。</p>
     */
    public static final String SYSTEM_MESSAGE_PREFIX = "【系统】";

    /**
     * 是否是系统拼进会话的控制语，而不是用户自己打的字。
     *
     * <p>用途是把它排除在任务信号提取之外：控制语只表达「上一步被放行了」，
     * 不表达任何新的交付目标。并发/拼装点都必须用 {@link #SYSTEM_MESSAGE_PREFIX} 统一前缀，
     * 不能各写各的字面量，否则这条判定失效且不会报错。</p>
     */
    public static boolean isSystemControlMessage(String text) {
        return text != null && text.stripLeading().startsWith(SYSTEM_MESSAGE_PREFIX);
    }

    public static final String TURN_TOOLS_HEADER = "# 本轮工具面";
    public static final String AGENT_LLM_NO_RESPONSE = "LLM 无响应/超时";
    public static final String AGENT_LLM_REFUSED = "模型未能给出可用答复，请换个说法再试。";
    public static final String AGENT_LLM_NETWORK_RECONNECT = "【系统通知】上次模型调用遇到临时网络问题，已自动重连。请继续刚才的任务。";
    public static final String AGENT_LLM_NETWORK_FAILED = "（模型连接异常，已自动重试但仍失败，请稍后再试或联系管理员）";
    public static final String AGENT_SUBTASK_PROMPT = "\n（已收到你的补充说明，正在融入当前任务...）\n";
    public static final String AGENT_DEAD_LOOP_DETECTED = "检测到连续重复调用，可能进入死循环，已自动停止。";
    public static final String AGENT_MEDIA_DELIVERY = "工具已直接返回媒体文件，无需额外保存。";
    public static final String AGENT_MAX_ITERATIONS_REACHED = "达到最大迭代次数限制，自动结束。";
    public static final String AGENT_TASK_UNFINISHED =
            "任务还没做完。已写入的部分在 workspace/，回复「继续」会接着当前进度做。";
    public static final String PLANNER_CONTINUE_HINT =
            "本段轮次用尽。接着当前工具上下文继续，不要重复已经完成的打开/导航。";
    public static final String PLANNER_FOCUS_RULES =
            "框架已过滤工具表。禁止 todo.set/clear；只能 update 当前 focusTodoIds。"
                    + "缺密钥或须用户提供信息时：todo update status=awaiting_confirm，"
                    + "然后直接向用户提问并结束本段；禁止为凑完成写无法执行的脚本。"
                    + "缺终端执行或 HTTP POST 时直接调用 exec_command / http_post，"
                    + "由框架向用户弹授权，不要改 awaiting_confirm，不要让用户去改配置或双击脚本。"
                    + "立刻执行本步，不要探盘或重建计划。";
    public static final String STOP_NEED_TODO_PLAN =
            "【收尾被拦截】这是复杂任务，你还没有用 todo(action=set) 写出带 done_when 的计划。"
                    + "下一轮必须先调用 todo set，再逐步执行。不要直接给最终结论。";
    public static final String STOP_INCOMPLETE_TODO =
            "【收尾被拦截】仍有未完成的子任务。请继续执行「当前子目标」，"
                    + "完成后 todo update 标 completed 并提供 evidence。"
                    + "缺密钥时 todo update awaiting_confirm 并直接向用户提问。";
    public static final String TASK_GRAPH_OWNED =
            "【任务图由规划器持有】禁止 todo.set/clear。只 update 当前 focus 步骤并附 evidence。"
                    + "本段只完成当前步骤，不要重建计划，也不要按整张清单收尾。";
    public static final String SSE_PERMISSION_ASK = "permission_ask";
    public static final String SSE_USER_QUESTION = "user_question";
    public static final String AGENT_PERM_ASK_WAIT =
            "需要你批准才能执行「%s」。请点击弹窗中的「批准并继续」，不要只点步骤确认。";
    /**
     * 「工具未执行，在等用户」的统一状态字。
     *
     * <p>工具结果有三种终态，不是一个布尔：成功 / 失败 / <b>未执行（等人工）</b>。
     * 过去没有第三种表示法，只好把「等人」塞成 {@code {"error": ...}} —— 于是所有
     * 靠「文本里有没有 error」判断失败的地方（{@code TraceRecorder.isFailedResult}
     * 及依赖它的反思提示、连续失败计数、轨迹状态）都会把「用户还没点批准」读成
     * 「工具执行失败」，进而给模型注入「请换策略、不要用相同参数重试」——
     * 而它其实只差用户点一下。</p>
     *
     * <p>这类结果的形状是 {@code {"status":"awaiting_user", ...}}，<b>不含 error 键</b>，
     * 所以按文本判失败的旧逻辑天然不会误判；{@code ToolResult.fromLegacy} 另有一支
     * 把它映射成 {@code ToolStatus.AWAITING_USER}。</p>
     */
    public static final String AWAITING_USER_STATUS = "awaiting_user";
    public static final String AGENT_PERM_ASK_TOOL_PENDING =
            "{\"status\":\"" + AWAITING_USER_STATUS + "\",\"tool\":\"%s\","
                    + "\"message\":\"工具 %s 需用户批准后才能执行。已向用户弹出授权。"
                    + "不要把步骤标为 awaiting_confirm，不要让用户双击脚本或去改配置。\"}";
    public static final String AGENT_USER_QUESTION_TOOL_PENDING =
            "{\"status\":\"" + AWAITING_USER_STATUS + "\",\"tool\":\"ask_user_question\","
                    + "\"message\":\"已向用户提问并等待回答。不要重试 ask_user_question，"
                    + "等用户下一条消息再继续。\"}";
    public static final String AGENT_CONTEXT_TRUNCATED = "【上下文已硬截断，部分早期内容丢失】";

    // ==================== MEMORY 记忆 ====================
    public static final String MEMORY_EMPTY_CONTENT = "内容不能为空。";
    public static final String MEMORY_DUPLICATE = "条目已存在（未重复添加）。";
    public static final String MEMORY_LIMIT_EXCEEDED = "记忆已达 %d/%d 字符。请先替换或删除旧条目。";
    public static final String MEMORY_NOT_FOUND = "没有找到匹配 '%s' 的条目。";
    public static final String MEMORY_ENTRY_ADDED = "条目已添加。";
    public static final String MEMORY_OLD_TEXT_EMPTY = "old_text 不能为空。";
    public static final String MEMORY_ENTRY_REPLACED = "条目已替换。";
    public static final String MEMORY_ENTRY_DELETED = "条目已删除。";
    public static final String MEMORY_USER_PROFILE = "用户画像";
    public static final String MEMORY_NOTES = "记忆笔记";
    public static final String MEMORY_MIDTERM = "跨会话中期记忆";
    public static final String MEMORY_RECALLED = "相关记忆（按当前对话召回 %d 条）";
    public static final String MEMORY_EMPTY = "（空）";

    // ==================== BROWSER 浏览器 ====================
    public static final String BROWSER_STARTED = "浏览器已启动";
    public static final String BROWSER_NAV_FAILED = "导航失败: %s";
    public static final String BROWSER_SNAPSHOT_FAILED = "获取快照失败: %s";
    public static final String BROWSER_CLICK_FAILED_EMPTY_REF = "点击失败: ref 为空";
    public static final String BROWSER_CLICK_FAILED_UNKNOWN_BY = "点击失败: 未知 by=%s，可用 ref|text|role|css|aria";
    public static final String BROWSER_CLICK_FAILED = "点击失败: ref=%s by=%s — %s";
    public static final String BROWSER_CLICK_FAILED_NOT_DIGIT = "点击失败: by=ref 需要数字编号，收到: %s";
    public static final String BROWSER_CLICK_FAILED_NO_REF = "点击失败: 快照中无 ref=%s。请先 browser_snapshot。";
    public static final String BROWSER_CLICK_FAILED_NO_NAME = "点击失败: ref=%s 无可用名称 [%s]。改用 by=css。";
    public static final String BROWSER_CLICK_FAILED_BY_NAME = "点击失败: ref=%s name=\"%s\" — %s";
    public static final String BROWSER_CLICK_SUCCESS = "点击成功 (%s): %s";
    public static final String BROWSER_INPUT_SUCCESS = "输入成功: ref=%s text=\"%s\"";
    public static final String BROWSER_INPUT_SUCCESS_PLACEHOLDER = "输入成功: placeholder=%s text=\"%s\"";
    public static final String BROWSER_INPUT_SUCCESS_LABEL = "输入成功: label=%s text=\"%s\"";
    public static final String BROWSER_INPUT_FAILED_NOT_FOUND = "输入失败: 未找到匹配 ref=%s 的输入框。请用 browser_snapshot 后用编号或 placeholder 重试。";
    public static final String BROWSER_INPUT_FAILED = "输入失败: %s";
    public static final String BROWSER_PRESS_SUCCESS = "按键成功: %s";
    public static final String BROWSER_PRESS_FAILED = "按键失败: %s";
    public static final String BROWSER_SCROLL_SUCCESS = "滚动成功: %s";
    public static final String BROWSER_SCROLL_FAILED = "滚动失败: %s";
    public static final String BROWSER_SCREENSHOT_SAVED = "截图已保存: %s";
    public static final String BROWSER_SCREENSHOT_FAILED = "截图失败: %s";
    public static final String BROWSER_PAGE_CLOSED = "浏览器页面已关闭: %s";
    public static final String BROWSER_EVAL_RESULT = "执行结果: %s";
    public static final String BROWSER_EVAL_FAILED = "执行失败: %s";
    public static final String BROWSER_EXTRACT_EMPTY = "抽取失败: 正文过短 url=%s";
    public static final String BROWSER_EXTRACT_FAILED = "抽取失败: %s";
    public static final String BROWSER_EMPTY_PAGE = "（页面为空）";

    // ==================== COMFYUI ====================
    public static final String COMFYUI_NOT_RUNNING = "ComfyUI 未运行或无法连接 (%s)";
    public static final String COMFYUI_NO_MODELS = "ComfyUI 中没有可用的 checkpoint 模型。请先在 ComfyUI/models/checkpoints/ 中放置模型文件。";
    public static final String COMFYUI_GET_MODELS_FAILED = "获取模型列表失败: %s";
    public static final String COMFYUI_SUBMIT_FAILED_NULL = "提交工作流失败，ComfyUI 可能未启动";
    public static final String COMFYUI_SUBMIT_FAILED = "提交工作流失败: %s";
    public static final String COMFYUI_NO_PROMPT_ID = "提交成功但未获取到 prompt_id";
    public static final String COMFYUI_EXEC_ERROR = "ComfyUI 执行出错: %s";
    public static final String COMFYUI_GENERATION_TIMEOUT = "图片生成超时(%d秒)，可能仍在处理中。用 comfyui_execute(action=status, prompt_id=\"%s\") 手动查询。";
    public static final String COMFYUI_TXT2IMG_FAILED = "文生图失败: %s";
    public static final String COMFYUI_IMG2IMG_FAILED = "图生图失败: %s";
    public static final String COMFYUI_IMG2VIDEO_FAILED = "图生视频失败: %s";
    public static final String COMFYUI_TTS_FAILED = "TTS 失败: %s";
    public static final String COMFYUI_IMAGE_NOT_FOUND = "图片文件不存在: %s";
    public static final String COMFYUI_IMAGE_UPLOADED = "图片已上传，可用于 img2img 工作流";
    public static final String COMFYUI_UPLOAD_FAILED = "上传图片失败: %s";
    public static final String COMFYUI_GET_WORKFLOW_FAILED = "获取工作流失败: %s";

    // ==================== IMAGE QUALITY CHECKER ====================
    public static final String IQC_IMAGE_NOT_FOUND = "生成图不存在: %s";
    public static final String IQC_FILE_TOO_SMALL = "文件过小";
    public static final String IQC_FILE_TOO_SMALL_MAYBE_FAILED = "文件过小，可能生成失败";
    public static final String IQC_MODEL_CALL_FAILED = "质检模型调用失败";
    public static final String IQC_MODEL_CALL_FAILED_DEFAULT_PASS = "质检模型调用失败，默认通过";
    public static final String IQC_CHECK_EXCEPTION = "质检异常";
    public static final String IQC_PROVIDE_PATH = "请提供图片路径";
    public static final String IQC_IMAGE_FILE_NOT_FOUND = "图片文件不存在: %s";

    // ==================== MCP ====================
    public static final String MCP_NOT_ENABLED = "MCP 未启用";
    public static final String MCP_SERVER_NOT_CONNECTED = "MCP 服务器未连接: %s";
    public static final String MCP_CALL_FAILED = "MCP 调用失败: %s";

    // ==================== NETWORK 网络安全 ====================
    public static final String NET_URL_EMPTY = "URL 为空";
    public static final String NET_URL_INVALID = "非法 URL: %s";
    public static final String NET_PROTOCOL_NOT_ALLOWED = "仅允许 http/https 协议";
    public static final String NET_HOST_MISSING = "URL 缺少 host";
    public static final String NET_SSRF_BLOCKED_HOST = "SSRF 防护：禁止访问内网/本地地址: %s";
    public static final String NET_SSRF_BLOCKED_RESOLVED = "SSRF 防护：解析到内网/本地地址: %s";
    public static final String NET_HOST_UNRESOLVABLE = "无法解析主机: %s";

    // ==================== SECURITY 安全扫描 ====================
    public static final String SEC_INVISIBLE_UNICODE = "拦截：内容包含隐形 unicode 字符 U+%04X（可能是注入攻击）。";
    public static final String SEC_THREAT_PATTERN = "拦截：内容匹配安全威胁模式。记忆条目会注入系统提示，不能包含注入或外泄载荷。";

    // ==================== FILE 文件 ====================
    public static final String FILE_EMPTY = "文件为空";
    public static final String FILE_TOO_LARGE = "文件过大，单文件上限 50MB（请压缩或拆分后重试）";
    public static final String FILE_INVALID_NAME = "非法文件名: %s";
    public static final String FILE_READ_FAILED = "文件读取失败";
    public static final String FILE_PATH_TRAVERSAL = "路径穿越攻击";

    // ==================== MEDIA ====================
    public static final String MEDIA_PROCESSING_ERROR = "媒体处理失败";

    // ==================== ROLE 角色 ====================
    public static final String ROLES_FILE_NOT_EXISTS = "roles.yml 不存在，角色系统将使用默认配置";
    public static final String ROLES_LOADED = "共加载 %d 个角色配置";
    public static final String ROLES_LOAD_FAILED = "加载 roles.yml 失败";

    // ==================== EVAL 评估 ====================
    public static final String EVAL_STARTED = "评估开始";
    public static final String EVAL_FINISHED = "评估结束";
    public static final String EVAL_CASE_NOT_FOUND = "未找到用例";
    public static final String EVAL_SKIP_INVALID = "跳过无效用例";
    public static final String EVAL_REPORT_TITLE = "评估报告";
    public static final String EVAL_TOTAL_CASES = "总用例";
    public static final String EVAL_PASSED = "通过";
    public static final String EVAL_DIMENSION_PASS_RATE = "分维度通过率";
    public static final String EVAL_FAILED_CASES = "失败用例";
    public static final String EVAL_RUN_ERROR = "运行错误";
    public static final String EVAL_REPORT_WRITTEN = "报告已写入";
    public static final String EVAL_REPORT_WRITE_FAILED = "写报告失败";

    // ==================== SSE 流式 ====================
    public static final String SSE_NOT_AUTHENTICATED = "Not authenticated";
    public static final String SSE_FORBIDDEN = "Forbidden";
    public static final String SSE_GONE = "gone";
    /** 轨迹页 SSE 事件名：新步骤已落库 */
    public static final String TRACE_SSE_EVENT = "trace";

    // ==================== AUTH 认证 ====================
    public static final String AUTH_NOT_AUTHENTICATED = "Not authenticated";
    public static final String AUTH_FORBIDDEN = "Forbidden";
    public static final String AUTH_USER_EXISTS = "用户已存在";
    public static final String AUTH_LOGIN_FAILED = "用户名或密码错误";
    public static final String AUTH_REGISTER_SUCCESS = "注册成功";
}
