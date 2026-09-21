package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出码语义：非 0 不等于失败。
 *
 * <p>这里每条断言都对应一个「会被读成工具坏了」的真实场景。改坏这些判定不会有编译错误，
 * 只会让模型陷入「收到失败 → 重试同一条命令 → 结果不变 → 撞上 allFailedRepeated 闸门中止整轮」。</p>
 */
class CommandSemanticsTest {

    @Test
    void grepExitOneMeansNoMatchNotFailure() {
        assertFalse(CommandSemantics.isFailure("grep -rn foo src/", 1));
        assertFalse(CommandSemantics.isFailure("findstr /s foo *.txt", 1));
        assertEquals("未匹配到内容", CommandSemantics.interpret("grep foo f.txt", 1).message());
    }

    @Test
    void grepExitTwoIsARealFailure() {
        // 2 才是「出错」（文件不存在、参数写错），不能一起放行
        assertTrue(CommandSemantics.isFailure("grep -rn foo src/", 2));
    }

    @Test
    void zeroIsAlwaysSuccess() {
        assertFalse(CommandSemantics.isFailure("grep foo f.txt", 0));
        assertFalse(CommandSemantics.isFailure("mvnw -q test", 0));
    }

    @Test
    void buildFailureStaysAFailure() {
        assertTrue(CommandSemantics.isFailure("mvnw -q -DskipTests package", 1));
        // 没有特殊语义时只给通用原因，不编「未匹配到内容」这类解释
        CommandSemantics.Interpretation it = CommandSemantics.interpret("mvnw -q package", 1);
        assertTrue(it.error());
        assertTrue(it.message().contains("1"));
    }

    @Test
    void diffAndTestUseExitCodeAsInformation() {
        assertFalse(CommandSemantics.isFailure("diff a.txt b.txt", 1));
        assertTrue(CommandSemantics.isFailure("diff a.txt b.txt", 2));
        assertFalse(CommandSemantics.interpret("test -f pom.xml", 1).error());
    }

    @Test
    void lookupCommandsOnlyHaveZeroAndOne() {
        assertFalse(CommandSemantics.isFailure("where java", 1));
        assertTrue(CommandSemantics.isFailure("where java", 2));
    }

    @Test
    void exitCodeBelongsToTheLastSegmentOfAPipeline() {
        // 退出码来自 more，而 more 没有特殊语义：这里仍判失败
        assertTrue(CommandSemantics.isFailure("findstr foo log.txt | more", 1));
        // 末段还是 grep：按 grep 的语义放行
        assertFalse(CommandSemantics.isFailure("cat f.txt | grep -v '^#'", 1));
    }

    @Test
    void wrapperDoesNotHideTheBaseCommand() {
        assertFalse(CommandSemantics.isFailure("timeout 30 grep foo f.txt", 1));
        assertFalse(CommandSemantics.isFailure("bash -c \"grep foo f.txt\"", 1));
    }

    @Test
    void toleratedNoteIsWrittenOnlyForNonErrorExits() {
        String note = CommandSemantics.toleratedNote("grep foo f.txt", 1);
        assertTrue(note.startsWith(CommandSemantics.TOLERATED_EXIT_PREFIX));
        assertTrue(note.contains("未匹配到内容"));
        assertTrue(CommandSemantics.isToleratedExitText(note));

        assertEquals("", CommandSemantics.toleratedNote("grep foo f.txt", 2), "真失败不该写提示行");
        assertEquals("", CommandSemantics.toleratedNote("ls -la", 1), "ls 的 1 没有特殊语义");
        assertEquals("", CommandSemantics.toleratedNote("grep foo f.txt", 0));
    }

    @Test
    void unknownCommandHasNoSpecialSemantics() {
        assertFalse(CommandSemantics.hasSpecialSemantics("mvnw -q package"));
        assertTrue(CommandSemantics.hasSpecialSemantics("grep foo f.txt"));
        assertTrue(CommandSemantics.hasSpecialSemantics("findstr /s foo *.txt | grep -v x"));
    }
}
