package com.miniagent.agent.coding;

import java.nio.file.Path;

/**
 * 一条代码诊断。
 *
 * @param file     出问题的文件
 * @param line     1-based 行号；解析不出来时给 0，不要编一个
 * @param severity 与 LSP 对齐的取值：{@code Error} / {@code Warning}
 * @param message  原始诊断文本，不要改写措辞 —— 模型见过的编译器报错长什么样，这里就该长什么样
 */
public record Diagnostic(Path file, int line, String severity, String message) {

    public String oneLine() {
        String name = file == null ? "?" : file.getFileName().toString();
        return (line > 0 ? name + ":" + line : name) + ": " + severity + ": " + message;
    }
}
