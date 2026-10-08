package com.miniagent.common;

/**
 * 统一错误码枚举。
 * <p>
 * 格式：{@code XX.YY.ZZ}
 * <ul>
 *   <li>XX — 模块（AUTH / CHAT / AGENT / MEMORY / TODO / FILE / MEDIA / BROWSER / MCP / CONFIG / SYSTEM）</li>
 *   <li>YY — 功能区（01=核心流程, 02=校验/权限, 03=外部调用 …）</li>
 *   <li>ZZ — 具体错误</li>
 * </ul>
 * 通过错误码即可定位：什么模块 → 什么功能 → 什么错误。
 */
public enum ErrorCode {

    // ==================== AUTH 认证授权 ====================
    AUTH_LOGIN_FAILED("AUTH.01.01", "登录失败"),
    AUTH_USER_EXISTS("AUTH.01.02", "用户已存在"),
    AUTH_REGISTER_DISABLED("AUTH.01.03", "注册功能已关闭"),
    AUTH_USERNAME_INVALID("AUTH.01.04", "用户名不合法"),
    AUTH_PASSWORD_REQUIRED("AUTH.01.05", "密码不能为空"),
    AUTH_PASSWORD_TOO_SHORT("AUTH.01.06", "密码长度不足"),
    AUTH_NOT_AUTHENTICATED("AUTH.02.01", "未登录"),
    AUTH_FORBIDDEN("AUTH.02.02", "无权限"),
    AUTH_SESSION_INVALID("AUTH.02.03", "会话无效"),

    // 云端账号服务（agent.auth.cloud.base-url）。YY 取 03 —— 按本文件开头的约定，
    // 03 表示"外部调用"。这一区分是必须的：前端要能分辨"密码错"（AUTH.01.01）
    // 和"云连不上"（AUTH.03.01）—— 前者提示用户改输入，后者提示用户查网络。
    // 混用 AUTH_LOGIN_FAILED 会让断网表现成"账号密码错误"，是最难排查的那种假象。
    AUTH_CLOUD_UNREACHABLE("AUTH.03.01", "云端账号服务不可达"),
    AUTH_CLOUD_REJECTED("AUTH.03.02", "云端账号服务拒绝该请求"),
    AUTH_CLOUD_INVALID_RESPONSE("AUTH.03.03", "云端账号服务返回内容无法解析"),
    AUTH_CLOUD_NOT_CONFIGURED("AUTH.03.04", "未配置云端账号服务"),

    // ==================== MEMBER 会员与充值 ====================
    // 归在 MEMBER 而不是复用 AUTH：AUTH 的编号空间表示"身份与权限"，
    // 而这一组是"付费与配额"。混进去之后 AUTH.01.0x 会同时表示
    // "用户名不合法"和"套餐不存在"，读日志的人得先在脑子里做一次分类。
    MEMBER_PLAN_NOT_FOUND("MEMBER.01.01", "套餐不存在"),
    MEMBER_PLAN_DISABLED("MEMBER.01.02", "套餐已下架"),
    MEMBER_ORDER_NOT_FOUND("MEMBER.01.03", "订单不存在"),
    MEMBER_ORDER_STATE_INVALID("MEMBER.01.04", "订单状态不允许该操作"),
    MEMBER_USER_NOT_FOUND("MEMBER.02.01", "用户不存在"),
    MEMBER_SUBSCRIPTION_NOT_FOUND("MEMBER.02.03", "没有生效中的订阅"),
    MEMBER_INTERNAL_KEY_MISSING("MEMBER.02.02", "会员接口未启用"),
    MEMBER_PAYMENT_DECLINED("MEMBER.03.01", "支付渠道拒绝了该请求"),

    // ==================== CHAT 对话/会话 ====================
    CHAT_MESSAGE_EMPTY("CHAT.01.01", "请输入有效内容"),
    CHAT_SESSION_NOT_FOUND("CHAT.02.01", "会话不存在"),
    CHAT_CONCURRENT_LIMIT("CHAT.03.01", "该会话有任务正在运行"),
    CHAT_PERSIST_FAILED("CHAT.04.01", "持久化会话历史失败"),
    CHAT_RENAME_FAILED("CHAT.05.01", "重命名会话失败"),
    CHAT_DELETE_FAILED("CHAT.05.02", "删除会话失败"),
    CHAT_MULTIMODAL_EMPTY("CHAT.01.02", "请输入文字或上传图片"),

    // ==================== AGENT 循环执行 ====================
    AGENT_MAX_ITERATIONS("AGENT.01.01", "达到最大迭代次数"),
    AGENT_LLM_TIMEOUT("AGENT.02.01", "模型调用超时"),
    AGENT_LLM_NO_RESPONSE("AGENT.02.02", "模型无响应"),
    AGENT_LLM_ERROR("AGENT.02.03", "模型调用失败"),
    AGENT_LLM_REFUSED("AGENT.02.04", "模型拒绝回答"),
    AGENT_TOOL_TIMEOUT("AGENT.03.01", "工具执行超时"),
    AGENT_TOOL_ERROR("AGENT.03.02", "工具执行失败"),
    AGENT_CONTEXT_OVERFLOW("AGENT.04.01", "上下文溢出"),
    AGENT_DEAD_LOOP("AGENT.05.01", "检测到死循环"),
    AGENT_STREAM_ERROR("AGENT.06.01", "流式推送失败"),
    AGENT_PLANNER_LOCK_LOST("AGENT.07.01", "规划会话锁丢失，已中止以免双实例冲突"),
    AGENT_PLANNER_GRAPH_INVALID("AGENT.07.02", "任务图未通过验收，无法调度"),

    // ==================== MEMORY 记忆管理 ====================
    MEMORY_EMPTY_CONTENT("MEMORY.01.01", "内容不能为空"),
    MEMORY_DUPLICATE("MEMORY.01.02", "条目已存在"),
    MEMORY_LIMIT_EXCEEDED("MEMORY.01.03", "记忆容量已达上限"),
    MEMORY_NOT_FOUND("MEMORY.01.04", "未找到匹配条目"),
    MEMORY_WRITE_FAILED("MEMORY.02.01", "记忆写入失败"),
    MEMORY_MIDTERM_UPDATE_FAILED("MEMORY.03.01", "中期记忆更新失败"),

    // ==================== TODO 任务待办 ====================
    TODO_NOT_FOUND("TODO.01.01", "待办项不存在"),
    TODO_INVALID_STATE("TODO.02.01", "待办项状态非法"),
    TODO_STOP_MISSING_PLAN("TODO.03.01", "复杂任务尚未建立计划"),
    TODO_STOP_INCOMPLETE("TODO.03.02", "仍有未完成的子任务"),

    // ==================== FILE 文件 ====================
    FILE_EMPTY("FILE.01.01", "文件为空"),
    FILE_TOO_LARGE("FILE.01.02", "文件过大"),
    FILE_MEDIA_UNSUPPORTED("FILE.01.03", "不支持的音视频格式"),
    FILE_INVALID_PATH("FILE.02.01", "非法文件路径"),
    FILE_PATH_TRAVERSAL("FILE.02.02", "路径穿越攻击"),
    FILE_READ_ERROR("FILE.03.01", "文件读取失败"),
    FILE_WRITE_ERROR("FILE.03.02", "文件写入失败"),
    FILE_NOT_FOUND("FILE.03.03", "文件不存在"),
    FILE_EXTRACT_ERROR("FILE.04.01", "文件内容提取失败"),
    FILE_UPLOAD_ERROR("FILE.05.01", "文件上传失败"),

    // ==================== MEDIA 媒体/图片生成 ====================
    MEDIA_GENERATION_FAILED("MEDIA.01.01", "图片生成失败"),
    MEDIA_GENERATION_TIMEOUT("MEDIA.01.02", "图片生成超时"),
    MEDIA_QUALITY_CHECK_FAILED("MEDIA.02.01", "图片质检失败"),
    MEDIA_UPLOAD_FAILED("MEDIA.03.01", "媒体上传失败"),
    MEDIA_NOT_FOUND("MEDIA.04.01", "媒体文件不存在"),

    // ==================== BROWSER 浏览器自动化 ====================
    BROWSER_START_FAILED("BROWSER.00.01", "浏览器启动失败"),
    BROWSER_NAVIGATE_FAILED("BROWSER.01.01", "页面导航失败"),
    BROWSER_SNAPSHOT_FAILED("BROWSER.01.02", "获取页面快照失败"),
    BROWSER_CLICK_FAILED("BROWSER.02.01", "点击操作失败"),
    BROWSER_TYPE_FAILED("BROWSER.02.02", "输入操作失败"),
    BROWSER_PRESS_FAILED("BROWSER.02.03", "按键操作失败"),
    BROWSER_SCROLL_FAILED("BROWSER.02.04", "滚动操作失败"),
    BROWSER_SCREENSHOT_FAILED("BROWSER.02.05", "截图失败"),
    BROWSER_EVALUATE_FAILED("BROWSER.02.06", "JS 执行失败"),
    BROWSER_TIMEOUT("BROWSER.03.01", "浏览器操作超时"),
    BROWSER_CLOSE_FAILED("BROWSER.04.01", "浏览器关闭失败"),

    // ==================== MCP 协议 ====================
    MCP_NOT_ENABLED("MCP.01.01", "MCP 未启用"),
    MCP_NO_SERVERS("MCP.01.02", "MCP 未配置服务器"),
    MCP_SERVER_NOT_FOUND("MCP.02.01", "未知 MCP 服务器"),
    MCP_CONNECTION_FAILED("MCP.02.02", "MCP 服务器连接失败"),
    MCP_CALL_FAILED("MCP.03.01", "MCP 工具调用失败"),
    MCP_EMPTY_RESULT("MCP.03.02", "MCP 返回空结果"),
    MCP_CONNECTION_CLOSED("MCP.04.01", "MCP 连接已关闭"),

    // ==================== CONFIG 配置 ====================
    CONFIG_MODEL_NOT_FOUND("CONFIG.01.01", "模型配置不存在"),
    CONFIG_INVALID("CONFIG.02.01", "配置参数无效"),
    CONFIG_DIR_CREATE_FAILED("CONFIG.03.01", "数据目录创建失败"),

    // ==================== SYSTEM 系统 ====================
    SYSTEM_INTERNAL_ERROR("SYSTEM.01.01", "系统内部错误"),
    SYSTEM_IO_ERROR("SYSTEM.02.01", "IO 操作失败"),
    SYSTEM_TIMEOUT("SYSTEM.03.01", "操作超时"),
    SYSTEM_SSRF_BLOCKED("SYSTEM.04.01", "SSRF 防护：禁止访问内网地址"),
    SYSTEM_SECURITY_VIOLATION("SYSTEM.04.02", "安全检查未通过"),
    SYSTEM_RATE_LIMITED("SYSTEM.05.01", "请求过于频繁"),

    // ==================== IMAGE 图片服务（第三方） ====================
    IMAGE_API_ERROR("IMAGE.01.01", "图片 API 调用失败"),
    IMAGE_PARSE_ERROR("IMAGE.02.01", "图片响应解析失败"),

    // ==================== EVAL 评估 ====================
    EVAL_CASE_NOT_FOUND("EVAL.01.01", "评估用例未找到"),
    EVAL_CASE_INVALID("EVAL.01.02", "评估用例无效"),
    EVAL_REPORT_WRITE_FAILED("EVAL.02.01", "评估报告写入失败");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 错误码，如 "CHAT.01.01" */
    public String getCode() {
        return code;
    }

    /** 默认中文消息 */
    public String getMessage() {
        return message;
    }

    /** 根据 code 查找枚举，找不到返回 null */
    public static ErrorCode ofCode(String code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return null;
    }
}
