package com.miniagent.eval;

/**
 * 单条断言。type 决定语义，其余字段按 type 取用。
 *
 * 支持的 type：
 *   response_contains   模型回复包含 value（子串，大小写不敏感）
 *   response_regex      模型回复匹配 value（正则）
 *   response_min_length 模型回复长度 >= number
 *   no_error            模型回复不包含 value（默认一组错误短语，用于判定「没翻车」）
 *   file_exists         path 指向的文件存在（相对项目根；支持 workspace/ 前缀）
 *   file_contains       path 文件存在且内容包含 value
 *
 * negate=true 时整条断言取反（例如「不应包含」）。
 */
public class EvalCheck {
    public String type;
    public String value;
    public String path;
    public int number;
    public boolean negate;
}
