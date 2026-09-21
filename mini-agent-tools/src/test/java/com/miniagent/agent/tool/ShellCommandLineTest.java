package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 命令行解析：段切分、包装器展开、基础命令定位。
 *
 * <p>这一层错了，上层两个判定会同时错：{@link CommandSemantics} 取错退出码的来源程序，
 * {@link CommandReadOnlyJudge} 把会写盘的命令当成只读。</p>
 */
class ShellCommandLineTest {

    @Test
    void pipelineIsSplitAndLastSegmentOwnsTheExitCode() {
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments("findstr foo log.txt | more");

        assertEquals(2, segments.size());
        assertEquals("findstr", segments.get(0).baseCommand());
        // 管道的退出码属于最后一段，不属于 findstr
        assertEquals("more", ShellCommandLine.lastSegment("findstr foo log.txt | more").baseCommand());
    }

    @Test
    void quotedSeparatorDoesNotSplitSegments() {
        // 引号里的 ; 是字面量，不是命令分隔符
        assertEquals(1, ShellCommandLine.segments("echo \"a;b\"").size());
        // 引号外的 ; 要切
        assertEquals(2, ShellCommandLine.segments("echo a; echo b").size());
    }

    @Test
    void cmdSlashCIsExpandedToTheRealCommand() {
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments("cmd /c dir");

        assertEquals(1, segments.size());
        assertEquals("dir", segments.get(0).baseCommand());
    }

    @Test
    void bashDashCIsExpandedAndInnerPipeIsSplit() {
        // 引号里的整串本身是一条命令行：必须展开后再切，否则基础命令会取成 bash
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments("bash -c \"grep x f.txt | wc -l\"");

        assertEquals(2, segments.size());
        assertEquals("grep", segments.get(0).baseCommand());
        assertEquals("wc", segments.get(1).baseCommand());
    }

    @Test
    void powershellCommandStringIsExpanded() {
        List<ShellCommandLine.Segment> segments =
                ShellCommandLine.segments("pwsh -Command \"Get-ChildItem -Force\"");

        assertEquals(1, segments.size());
        assertEquals("get-childitem", segments.get(0).baseCommand());
    }

    @Test
    void wrapperOptionsAreSkippedBeforeTheCommandName() {
        // timeout 30 / env VAR=val / nice -n 10：这些前缀都不是命令名
        assertEquals("git", ShellCommandLine.segments("timeout 30 git status").get(0).baseCommand());
        assertEquals("git", ShellCommandLine.segments("env FOO=1 git status").get(0).baseCommand());
        assertEquals("ls", ShellCommandLine.segments("nice -n 10 ls -la").get(0).baseCommand());
    }

    @Test
    void segmentTokensStartAtTheBaseCommand() {
        // 子命令是通过「tokens 里第一个非选项」取的，前缀没剥掉就会取到 30
        assertEquals(List.of("git", "status"), ShellCommandLine.segments("timeout 30 git status").get(0).tokens());
        assertEquals(List.of("ls", "-la"), ShellCommandLine.segments("nice -n 10 ls -la").get(0).tokens());
    }

    @Test
    void pathAndExtensionAreStripped() {
        assertEquals("grep", ShellCommandLine.stripPathAndExtension("/usr/bin/grep"));
        assertEquals("rg", ShellCommandLine.stripPathAndExtension("C:\\tools\\rg.exe"));
        assertEquals("cmd", ShellCommandLine.stripPathAndExtension("C:\\Windows\\System32\\cmd.exe"));
    }

    @Test
    void metacharacterScanCoversHandlerMergingAndQuotedWrappers() {
        assertNull(ShellCommandLine.unsafeMetacharacter("ls 2>&1"), "句柄合并不算写盘");
        assertEquals(">", ShellCommandLine.unsafeMetacharacter("ls > out.txt"));
        assertEquals(">", ShellCommandLine.unsafeMetacharacter("bash -c \"ls > out.txt\""),
                "重定向躲在引号里也要扫出来");
        assertEquals("$(", ShellCommandLine.unsafeMetacharacter("echo $(date)"));
        assertEquals("`", ShellCommandLine.unsafeMetacharacter("echo `date`"));
        assertEquals("<<", ShellCommandLine.unsafeMetacharacter("cat <<EOF"));
    }

    @Test
    void redirectAmpersandIsNotABackgroundSeparator() {
        // 2>&1 里的 & 属于重定向。当成后台运行符就会切成 "ls 2>" 和 "1"，
        // 第二段基础命令变成 "1"，最平常的错误流合并被判成「不是只读」。
        assertEquals(1, ShellCommandLine.segments("ls 2>&1").size());
        assertEquals("2>&1", ShellCommandLine.segments("ls 2>&1").get(0).tokens().get(1));
        assertEquals(1, ShellCommandLine.segments("ls &> out.txt").size());
        // 真正的后台运行符仍要切
        assertEquals(2, ShellCommandLine.segments("echo a & echo b").size());
    }

    @Test
    void simpleCommandDetection() {
        assertEquals(true, ShellCommandLine.isSimple("git status"));
        assertEquals(false, ShellCommandLine.isSimple("git status && ls"));
        assertEquals(null, ShellCommandLine.lastSegment("   "));
        assertNotNull(ShellCommandLine.segments("ls").get(0));
    }
}
