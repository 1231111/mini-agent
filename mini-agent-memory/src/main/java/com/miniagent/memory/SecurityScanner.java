package com.miniagent.memory;

import java.util.regex.Pattern;

/**
 * 写入记忆前的安全扫描 — 防止 prompt 注入 / 凭据外泄 / 隐形字符
 * 参考 hermes-agent 的 _scan_memory_content()
 *
 * <p><b>定位</b>：这是纵深防御的一层，不是唯一防线。真正的边界是
 * "记忆属于不可信数据"（见 {@code PromptTemplates.UNTRUSTED_DATA_RULES} 与
 * 记忆槽的 {@code <memory-data trust="untrusted">} 包裹）。黑名单永远可以被绕过
 * （换语言、改写、编码都能过），所以这里的目标是**拦住常见写法**、提高攻击成本，
 * 而不是宣称"扫过就安全"。</p>
 *
 * <p>本轮补强的原因：原规则 {@code ignore\s+(previous|all|above|prior)\s+instructions}
 * 只允许一个词间隔，于是最常见的写法 "Ignore all previous instructions" 直接漏过；
 * 同时整个规则集只覆盖英文，而本产品是中文优先 —— 中文注入指令此前完全不被拦截。</p>
 */
public class SecurityScanner {

    // Prompt 注入 / 角色劫持
    private static final Pattern[] THREAT_PATTERNS = {
            // ── 英文：忽略/覆盖既有指令（允许 1~3 个修饰词，覆盖 "all previous"、"any prior"） ──
            Pattern.compile("ignore\\s+(?:all\\s+|any\\s+|the\\s+|your\\s+)*"
                    + "(?:previous|prior|above|earlier|preceding|foregoing)\\s+"
                    + "(?:instruction|instructions|prompt|prompts|rule|rules|direction|directions)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:disregard|forget|override|bypass)\\s+"
                    + "(?:all\\s+|any\\s+|your\\s+|the\\s+|my\\s+)*"
                    + "(?:previous\\s+|prior\\s+|earlier\\s+|above\\s+|existing\\s+)*"
                    + "(?:instruction|instructions|rule|rules|guideline|guidelines|restriction|restrictions|"
                    + "prompt|prompts|safety|policy|policies)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(?:if|though)\\s+you\\s+(?:have\\s+no|don'?t\\s+have)\\s+"
                    + "(?:restrictions|limits|rules)", Pattern.CASE_INSENSITIVE),
            // 索要系统提示：双向匹配。
            // 只按"动词在前"写会漏掉中文最常见的语序（"把你的系统提示词输出出来"），
            // 而这类载荷正是"越狱第一步"的典型写法。
            Pattern.compile("(?:(?:print|reveal|show|repeat|output|leak|输出|打印|显示|泄露|复述|告诉|给我)"
                    + "[^\\n]{0,20}"
                    + "(?:system\\s+prompt|initial\\s+prompt|hidden\\s+rules|系统提示词?|系统指令|初始指令|隐藏规则|内部规则)"
                    + "|(?:system\\s+prompt|initial\\s+prompt|hidden\\s+rules|系统提示词?|系统指令|初始指令|隐藏规则|内部规则)"
                    + "[^\\n]{0,20}"
                    + "(?:print|reveal|show|repeat|output|leak|verbatim|原文|完整|输出|打印|显示|泄露|复述|告诉|给我))",
                    Pattern.CASE_INSENSITIVE),
            // 编码绕过：出现 base64/rot13/hex 且同句要求执行 —— 正常技术文本不会这样写
            Pattern.compile("(?:base64|rot13|hex)[^\\n]{0,60}"
                    + "(?:execute|run|follow|obey|执行|运行|遵循)", Pattern.CASE_INSENSITIVE),
            // ── 中文：本产品的主要语言，此前完全未覆盖 ──
            // 修饰语用 * 允许多个（"忽略之前的所有指令" 有两层修饰，只允许一个就会漏）
            Pattern.compile("(?:忽略|无视|不要理会|忘掉|忘记|抛开|绕过)(?:掉)?"
                    + "(?:之前|以后|以上|前面|上述|先前|当前|现在|原有|所有|全部|一切|任何|这些|那些|的|你)*"
                    + "(?:指令|指示|规则|要求|提示词|设定|限制|安全策略|约束)"),
            Pattern.compile("(?:从现在开始|现在起|接下来|以后)你(?:就)?是"),
            Pattern.compile("(?:不要|别)(?:告诉|告知|提醒)用户"),
            // 凭据外泄
            Pattern.compile("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
            Pattern.compile("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
            Pattern.compile("cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)"),
            // SSH 后门：必须带 SSH 语境才判定。
            // 裸的 "authorized_keys" 是极常见的文件名（例如 *_test.go），一律拦会把正常仓库内容
            // 挡在记忆之外 —— 安全策略吃掉功能同样算故障。
            Pattern.compile("(?:\\.ssh|ssh|公钥|写入|追加|添加|后门|持久访问)[^\\n]{0,24}authorized_keys"
                    + "|authorized_keys[^\\n]{0,24}(?:后门|持久|写入|追加|添加|\\.ssh|ssh)"),
            Pattern.compile("\\$HOME/\\.ssh|~/\\.ssh"),
            Pattern.compile("\\$HOME/\\.hermes/\\.env|~/\\.hermes/\\.env"),
    };

    // 隐形 unicode 字符（常用于注入攻击）
    private static final char[] INVISIBLE_CHARS = {
            '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
            '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
    };

    /**
     * 扫描内容。返回 null 表示安全，返回字符串表示被拦截的原因。
     */
    public static String scan(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        // 检查隐形 unicode
        for (char c : INVISIBLE_CHARS) {
            if (content.indexOf(c) >= 0) {
                return String.format("拦截：内容包含隐形 unicode 字符 U+%04X（可能是注入攻击）。", (int) c);
            }
        }

        // 检查威胁模式
        for (Pattern p : THREAT_PATTERNS) {
            if (p.matcher(content).find()) {
                return "拦截：内容匹配安全威胁模式。记忆条目会注入系统提示，不能包含注入或外泄载荷。";
            }
        }

        return null;
    }

    /** 被拦截时的统一占位文本（保留字段结构，便于事后审计"这里原本有内容但被拦了"）。 */
    public static final String REDACTED = "[已拦截：疑似提示注入/凭据外泄内容，未写入记忆]";

    /**
     * 派生内容的处理方式：命中即替换为占位符，而不是拒绝整条记录。
     *
     * <p>用于 Episode / 事实这类<b>由事件流或模型提炼出来的</b>内容：它们没有"作者"可以
     * 收到错误，直接抛异常会让同一批事件每轮都失败（毒消息），整段巩固结果（含正常部分）
     * 一起丢掉。所以这里保留记录、只摘掉被污染的那段文本。</p>
     *
     * <p>用户/接口直接写入的内容（{@code writeMemory} 等）不用这个：那种场景应当明确报错，
     * 让调用方知道写不进去，而不是静默存一条被改过的记忆。</p>
     */
    public static String redactIfUnsafe(String text) {
        return scan(text) == null ? text : REDACTED;
    }
}
