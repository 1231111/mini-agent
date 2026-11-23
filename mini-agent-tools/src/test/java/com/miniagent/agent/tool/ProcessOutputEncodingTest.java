package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessOutputEncodingTest {

    @Test
    void parsesWindowsCodePageFromLocalizedChcpOutput() {
        assertThat(ProcessOutputEncoding.parseWindowsCodePage("Active code page: 936\r\n"))
                .isEqualTo(936);
        assertThat(ProcessOutputEncoding.parseWindowsCodePage("活动代码页: 65001\r\n"))
                .isEqualTo(65001);
        assertThat(ProcessOutputEncoding.parseWindowsCodePage("Unable to get code page"))
                .isEqualTo(-1);
    }

    @Test
    void usesDetectedWindowsCodePageForNativeCommands() {
        Charset gbk = ProcessOutputEncoding.resolve("auto", "cmd /c dir", true, 936);
        assertThat(gbk).isEqualTo(Charset.forName("GBK"));
        assertThat(ProcessOutputEncoding.resolve("auto", "cmd /c dir", true, 65001))
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(ProcessOutputEncoding.resolve("auto", "ls", false, 936))
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void usesUtf8ForCommonUtf8RuntimesEvenWhenWindowsConsoleUsesGbk() {
        String expected = "FAIL JS 引用缺失元素";
        byte[] nodeOutput = expected.getBytes(StandardCharsets.UTF_8);
        Charset selected = ProcessOutputEncoding.resolve("auto", "node check-ui.js", true, 936);

        assertThat(selected).isEqualTo(StandardCharsets.UTF_8);
        assertThat(new String(nodeOutput, selected)).isEqualTo(expected);
    }

    @Test
    void decodesLegacyGbkOutputAndHonorsExplicitEncoding() {
        String expected = "命令输出：文件不存在";
        byte[] nativeOutput = expected.getBytes(Charset.forName("GBK"));
        Charset detected = ProcessOutputEncoding.resolve("auto", "legacy-tool.exe", true, 936);

        assertThat(new String(nativeOutput, detected)).isEqualTo(expected);
        assertThat(ProcessOutputEncoding.resolve("UTF-8", "legacy-tool.exe", true, 936))
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(ProcessOutputEncoding.resolve("GB18030", "legacy-tool.exe", true, 936))
                .isEqualTo(Charset.forName("GB18030"));
    }

    @Test
    void rejectsUnsupportedExplicitEncoding() {
        assertThatThrownBy(() -> ProcessOutputEncoding.resolve("not-a-codepage", "tool", true, 936))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的输出编码");
    }
}
