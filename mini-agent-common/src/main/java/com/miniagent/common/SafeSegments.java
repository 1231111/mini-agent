package com.miniagent.common;

import java.util.regex.Pattern;

/**
 * 单个路径段的合法性校验（文件名、目录名这类"要拼进路径的一段"）。
 *
 * <p><b>规则</b>：1-100 个字符，只允许字母、数字、{@code .}、{@code _}、{@code -}，
 * 且必须以字母或数字开头。</p>
 *
 * <p><b>"必须以字母数字开头"这一条同时挡掉三类逃逸</b>：</p>
 * <ol>
 *   <li>{@code .} 与 {@code ..} —— 相对路径逃逸（拼进 {@code resolve()} 就往上跳目录）；</li>
 *   <li>{@code .hidden} 这类点开头的名字；</li>
 *   <li>任何以分隔符或盘符开头的绝对路径（{@code /etc/x}、{@code C:\x}、{@code ..\x}）。</li>
 * </ol>
 *
 * <p>为什么要有这个公共类：同一条规则此前在 {@code SessionIds}（会话名）、
 * 会话页控制器（上传文件名）、以及 skill 的名字/分类上各写了一遍。前两处在 app 模块，
 * 而 skill 在 tools 模块、不能反向依赖 app —— 所以把规则上移到这里，各处共用一份。</p>
 */
public final class SafeSegments {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private SafeSegments() {
    }

    public static boolean isValid(String segment) {
        return segment != null && SAFE.matcher(segment).matches();
    }

    /**
     * 校验并返回原值，不合法直接抛 —— 用在"绝不允许带进路径"的入口。
     *
     * @param what 出现在错误消息里的字段名（如"技能名"、"分类"），让人知道该改哪个参数
     */
    public static String requireValid(String segment, String what) {
        if (!isValid(segment)) {
            throw new IllegalArgumentException(what
                    + " 只能由 1-100 个字母、数字、'.'、'_'、'-' 组成，且必须以字母或数字开头，当前值: "
                    + segment);
        }
        return segment;
    }
}
