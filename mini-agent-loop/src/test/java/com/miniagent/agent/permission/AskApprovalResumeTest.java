package com.miniagent.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批状态机：待批准 token 只有三种终态 —— 被批准（续跑 / 持久化）、被拒绝、超时或取消。
 *
 * <p>这里每条断言都对应一次真实故障：批准返回成功但工具没跑、批准后下一轮又问一次、
 * 超时弹窗再点一次仍然「成功」、拒绝之后 token 还能被消费。</p>
 */
class AskApprovalResumeTest {

    private static final String TOOL = "exec_command";
    private static final String ARGS = "{\"command\":\"echo hi\"}";

    @Test
    void approvalWithoutLiveWaiterPersistsExactArgumentGrant() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 60_000L);

        // 没有绑定等待点时（旧客户端、非等待调用）也必须真的记下这次授权，
        // 否则就是「批准返回成功、下一次仍然要批准」—— 用户看到的就是无限弹窗。
        assertTrue(store.grantAskToken("s", pending.token(), TOOL).isPresent());
        assertTrue(store.isAskGranted("s", TOOL, ARGS));
        assertFalse(store.isAskGranted("s", TOOL, "{\"command\":\"echo bye\"}"),
                "批准只对点击时展示的参数生效");
    }

    @Test
    void awaitedApprovalWakesWaiterAndIsNotReplayable() throws Exception {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        assertTrue(store.attachAskResume("s", pending.token()));
        AtomicReference<Optional<AskGrant>> seen = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            try {
                seen.set(store.awaitAskApproval("s", pending.token(), 5_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(50L);
        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isPresent());
        waiter.join(2_000L);

        assertFalse(waiter.isAlive(), "批准必须唤醒原调用，而不是让它等到超时");
        assertTrue(seen.get() != null && seen.get().isPresent());
        assertFalse(store.isAskGranted("s", TOOL, ARGS),
                "单次续跑批准不得写成可重放授权");
        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isEmpty(),
                "token 单次使用");
    }

    @Test
    void resumedApprovalRequiresALiveWaiter() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);

        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isEmpty(),
                "没有等待调用点时不得按续跑批准");
        // 失败不能顺手消费掉 token：旧客户端仍可走持久化批准路径
        assertTrue(store.grantAskToken("s", pending.token(), TOOL).isPresent());
    }

    @Test
    void denialRemovesTokenEvenWithoutWaiter() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);

        assertTrue(store.denyAskToken("s", pending.token(), TOOL));
        assertTrue(store.grantAskToken("s", pending.token(), TOOL).isEmpty(),
                "拒绝过的 token 不能再被批准");
        assertFalse(store.isAskGranted("s", TOOL, ARGS));
    }

    @Test
    void crossSessionTokenCannotBeApproved() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s1", TOOL, ARGS, 30_000L);

        assertTrue(store.grantAskToken("s2", pending.token(), TOOL).isEmpty());
        assertTrue(store.grantAskTokenForResume("s2", pending.token(), TOOL).isEmpty());
        assertFalse(store.denyAskToken("s2", pending.token(), TOOL));
        assertTrue(store.grantAskToken("s1", pending.token(), TOOL).isPresent());
    }

    @Test
    void mismatchedToolCannotConsumeOrApprove() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);

        assertTrue(store.grantAskTokenForResume("s", pending.token(), "write_file").isEmpty());
        assertFalse(store.denyAskToken("s", pending.token(), "write_file"));
        assertTrue(store.grantAskToken("s", pending.token(), TOOL).isPresent(),
                "工具名不匹配不应消费 token");
    }

    @Test
    void waitTimeoutIsTerminalAndTokenCannotBeApprovedLater() throws Exception {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        assertTrue(store.attachAskResume("s", pending.token()));

        long started = System.nanoTime();
        assertTrue(store.awaitAskApproval("s", pending.token(), 30L).isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L);

        // 超时后迟到的批准必须失败；否则用户会在已经放弃的弹窗上看到「批准成功」
        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isEmpty());
        assertTrue(store.grantAskToken("s", pending.token(), TOOL).isEmpty());
    }

    @Test
    void interruptedWaitReleasesToken() throws Exception {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        assertTrue(store.attachAskResume("s", pending.token()));
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            try {
                store.awaitAskApproval("s", pending.token(), 5_000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        waiter.start();
        Thread.sleep(50L);
        waiter.interrupt();
        waiter.join(2_000L);

        assertTrue(interrupted.get());
        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isEmpty(),
                "取消后的 token 必须失效，不能留下无人消费的批准");
    }

    @Test
    void duplicateAttachDoesNotStealThePendingApproval() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);

        assertTrue(store.attachAskResume("s", pending.token()));
        assertFalse(store.attachAskResume("s", pending.token()),
                "同一条 token 只能绑定一个等待点");
        assertTrue(store.hasAttachedAskResume("s", pending.token()),
                "重复绑定失败不能破坏第一个等待点");
        assertTrue(store.grantAskTokenForResume("s", pending.token(), TOOL).isPresent());
    }

    @Test
    void pendingTokenIsReusedForTheSameInvocation() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant first = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        AskGrant again = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        AskGrant otherArgs = store.issueAskGrant("s", TOOL, "{\"command\":\"ls\"}", 30_000L);

        assertEquals(first.token(), again.token(), "同一调用重复询问不应产生新 token");
        assertFalse(first.token().equals(otherArgs.token()), "不同参数必须是不同 token");
    }

    /**
     * 全自动在任务运行中生效时，已经弹出去的那一个等待点必须自己放行。
     *
     * <p>后续步骤靠 {@code PermissionPolicy.isFullAuto} 免批，但**已经发起**的调用还阻塞在
     * 等待点上、弹窗也还亮着。不放行的话，用户打开两个开关之后仍然卡在一个
     * 已经没必要的授权框上 —— 这正是"明明开了自动还在问"的最后一环。</p>
     */
    @Test
    void releasingPendingAsksWakesTheLiveWaiter() throws Exception {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant pending = store.issueAskGrant("s", TOOL, ARGS, 30_000L);
        assertTrue(store.attachAskResume("s", pending.token()));
        AtomicReference<Optional<AskGrant>> seen = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            try {
                seen.set(store.awaitAskApproval("s", pending.token(), 5_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(50L);

        assertEquals(1, store.releasePendingAsks("s"));
        waiter.join(2_000L);

        assertFalse(waiter.isAlive(), "放行必须唤醒原调用，而不是让它等到超时");
        assertTrue(seen.get() != null && seen.get().isPresent(),
                "原调用要拿到批准结果继续执行");
        assertFalse(store.isAskGranted("s", TOOL, ARGS),
                "放行是会话内的一次性动作，不能落成可重放授权");
        assertEquals(0, store.releasePendingAsks("s"), "重复调用不应重复计数");
    }

    /** 放行只作用于本会话：别的会话的等待点必须继续等，不能被顺手批掉。 */
    @Test
    void releasingPendingAsksDoesNotCrossSessions() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant mine = store.issueAskGrant("s1", TOOL, ARGS, 30_000L);
        AskGrant other = store.issueAskGrant("s2", TOOL, ARGS, 30_000L);
        assertTrue(store.attachAskResume("s1", mine.token()));
        assertTrue(store.attachAskResume("s2", other.token()));

        assertEquals(1, store.releasePendingAsks("s1"));
        assertTrue(store.hasAttachedAskResume("s2", other.token()),
                "另一个会话的等待点必须原样保留");
        assertTrue(store.denyAskToken("s2", other.token(), TOOL),
                "未被放行的等待着仍可正常处理");
    }
}
