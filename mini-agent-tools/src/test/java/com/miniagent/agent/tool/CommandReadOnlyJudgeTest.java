package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令级只读判定。
 *
 * <p>判宽了的后果不是「并发少一点」，而是把一条真会写盘的命令标成「可安全重试」——
 * 超时强杀后重跑，数据就没了。所以这里的断言分成两组：该放行的（最平常的观察类命令）
 * 必须放行，该拦的（任何一处可能写盘）必须拦住。</p>
 */
class CommandReadOnlyJudgeTest {

    // ─── 必须放行：因为「一律不放行」会让只读并行永远不生效 ───

    @Test
    void plainObservationCommandsAreReadOnly() {
        assertTrue(CommandReadOnlyJudge.isReadOnly("ls -la"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("cat pom.xml"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("dir C:\\repo"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("git status"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("git log --oneline -20"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("git diff --stat"));
    }

    @Test
    void chainedObservationCommandsAreReadOnly() {
        assertTrue(CommandReadOnlyJudge.isReadOnly("cd /d repo && git status"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("find . -type f | head -20"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("cat f.txt | grep -v '^#' | wc -l"));
    }

    @Test
    void handlerMergingIsNotAWrite() {
        // 2>&1 只是合并输出流，不产生文件
        assertTrue(CommandReadOnlyJudge.isReadOnly("ls 2>&1"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("git status 2>&1"));
        // &> 是真重定向，必须拦住
        assertFalse(CommandReadOnlyJudge.isReadOnly("ls &> out.txt"));
    }

    @Test
    void gitFlagGatedSubcommandsAreReadOnlyWithAReadFlag() {
        assertTrue(CommandReadOnlyJudge.isReadOnly("git branch --list"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("git config --get user.name"));
    }

    @Test
    void grepShortOutputIsNotAWriteFlag() {
        // -o 在 sort 上是写盘，在 grep 上是「只打印匹配部分」
        assertTrue(CommandReadOnlyJudge.isReadOnly("grep -o 'foo' f.txt"));
    }

    @Test
    void boundedObservationCommandsAreReadOnly() {
        assertTrue(CommandReadOnlyJudge.isReadOnly("tail -n 20 app.log"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("ping -c 2 127.0.0.1"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("where java"));
        assertTrue(CommandReadOnlyJudge.isReadOnly("pwsh -Command \"Get-ChildItem -Force\""));
    }

    // ─── 必须拦住 ───

    @Test
    void writingCommandsAreNotReadOnly() {
        assertFalse(CommandReadOnlyJudge.isReadOnly("rm -rf build/"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("del /q a.txt"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("mvnw -q -DskipTests package"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git commit -m x"));
    }

    @Test
    void anyWritingSegmentRejectsTheWholeCommand() {
        // 只看第一段会漏掉后半截
        assertFalse(CommandReadOnlyJudge.isReadOnly("ls && rm -rf build/"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git status && git checkout -- ."));
    }

    @Test
    void redirectionAndSubstitutionAreRejected() {
        assertFalse(CommandReadOnlyJudge.isReadOnly("ls > out.txt"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("echo hi >> log.txt"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("echo $(date)"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("echo `date`"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("cat <<EOF"));
    }

    @Test
    void writeFlagsOnReadOnlyCommandsAreRejected() {
        assertFalse(CommandReadOnlyJudge.isReadOnly("find . -name '*.tmp' -delete"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("find . -name '*.log' -exec rm {} ;"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("sort -o sorted.txt input.txt"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git diff --output=changes.patch"));
    }

    @Test
    void gitSubcommandsThatMayWriteAreRejected() {
        // 不带只读开关时 git branch 可能是在创建分支
        assertFalse(CommandReadOnlyJudge.isReadOnly("git branch new-feature"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git config user.name someone"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git push origin main"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("git"));
    }

    @Test
    void neverTerminatingCommandsAreRejected() {
        // 进并行批会把整批拖到超时
        assertFalse(CommandReadOnlyJudge.isReadOnly("tail -f app.log"));
        assertFalse(CommandReadOnlyJudge.isReadOnly("ping 127.0.0.1"));
    }

    @Test
    void wrapperQuotedPayloadIsJudgedByTheRealCommand() {
        // 只把 cmd / bash 剥掉、把引号里的整串当参数，会把 rm 藏起来
        assertFalse(CommandReadOnlyJudge.isReadOnly("bash -c \"rm -rf build/ && ls\""));
        assertFalse(CommandReadOnlyJudge.isReadOnly("bash -c \"ls > out.txt\""));
        assertTrue(CommandReadOnlyJudge.isReadOnly("bash -c \"git status && ls\""));
        assertTrue(CommandReadOnlyJudge.isReadOnly("cmd /c dir"));
    }

    @Test
    void emptyCommandIsNotReadOnly() {
        assertFalse(CommandReadOnlyJudge.isReadOnly(null));
        assertFalse(CommandReadOnlyJudge.isReadOnly("   "));
    }

    @Test
    void reasonIsAvailableForDiagnostics() {
        assertNull(CommandReadOnlyJudge.reasonNotReadOnly("git status"));
        assertNotNull(CommandReadOnlyJudge.reasonNotReadOnly("rm -rf build/"));
        assertTrue(CommandReadOnlyJudge.reasonNotReadOnly("tail -f a.log").contains("结束"));
    }
}
