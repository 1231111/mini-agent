package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「始终允许」会话级授权的 Store 侧行为。 */
class SessionPermissionStoreAlwaysAllowTest {

    @Test
    void compatStateConstructorsDefaultAlwaysToEmpty() {
        // 旧 6 参数与 7 参数构造器必须仍能编译，且新字段默认空集合。
        SessionPermissionPersistence.State six = new SessionPermissionPersistence.State(
                "default", false, Set.of("a"), "dangerous", null, 3L);
        SessionPermissionPersistence.State seven = new SessionPermissionPersistence.State(
                "ask", true, Set.of("a"), Map.of(), "auto", "block", 4L);

        assertTrue(six.alwaysAllowedTools().isEmpty());
        assertTrue(seven.alwaysAllowedTools().isEmpty());
        assertEquals(3L, six.version());
        assertEquals(4L, seven.version());
        // canonical 8 参数才是带 always 的形态。
        SessionPermissionPersistence.State eight = new SessionPermissionPersistence.State(
                "ask", true, Set.of("a"), Map.of(), "auto", "block",
                Set.of("exec_command"), 5L);
        assertEquals(Set.of("exec_command"), eight.alwaysAllowedTools());
        assertEquals(5L, eight.version());
    }

    @Test
    void alwaysAllowedToolSkipsArgumentDigest() {
        SessionPermissionStore store = new SessionPermissionStore();

        // 库里根本没有任何 AskGrant（更谈不上 argumentDigest 精确匹配）。
        assertFalse(store.isAskGranted("s1", "exec_command",
                "{\"command\":\"echo ok\"}"));

        store.alwaysAllow("s1", "exec_command");

        assertTrue(store.isAlwaysAllowed("s1", "exec_command"));
        assertEquals(Set.of("exec_command"), store.alwaysAllowedTools("s1"));
        // 「始终允许」命中后不再要求参数摘要：任意 arguments 都直接放行。
        assertTrue(store.isAskGranted("s1", "exec_command",
                "{\"command\":\"echo ok\"}"));
        assertTrue(store.isAskGranted("s1", "exec_command",
                "{\"command\":\"完全不同的一次调用\"}"));
        assertTrue(store.isAskGranted("s1", "exec_command", ""));
        // 未加入 always 的工具不会被误放行。
        assertFalse(store.isAskGranted("s1", "write_file", "{}"));
        assertFalse(store.isAlwaysAllowed("s1", "write_file"));
    }

    @Test
    void revokeRestoresPerInvocationAsking() {
        SessionPermissionStore store = new SessionPermissionStore();
        String exactArgs = "{\"path\":\"a.txt\"}";
        AskGrant pending = store.issueAskGrant("s1", "write_file", exactArgs, 60_000L);
        assertTrue(store.grantAskToken("s1", pending.token()).isPresent());

        store.alwaysAllow("s1", "write_file");
        // always 在时，参数摘要不匹配也放行。
        assertTrue(store.isAskGranted("s1", "write_file", "{\"path\":\"b.txt\"}"));

        store.revokeAlwaysAllow("s1", "write_file");

        assertFalse(store.isAlwaysAllowed("s1", "write_file"));
        assertTrue(store.alwaysAllowedTools("s1").isEmpty());
        // 撤销后恢复逐次询问：只有当初那条精确参数摘要仍被授权。
        assertTrue(store.isAskGranted("s1", "write_file", exactArgs));
        assertFalse(store.isAskGranted("s1", "write_file", "{\"path\":\"b.txt\"}"));
        assertFalse(store.isAskGranted("s1", "write_file", exactArgs + " "));
    }

    @Test
    void alwaysStateSurvivesRestartAndCasVersionIncrements() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        SessionPermissionStore first = new SessionPermissionStore(persistence);

        assertEquals(0L, persistence.loadOrCreate("s1").version());
        first.alwaysAllow("s1", "read_file");

        // 走 update() 的 CAS：版本号必须 +1，且 always 集合进持久化状态。
        SessionPermissionPersistence.State afterAllow =
                persistence.loadOrCreate("s1");
        assertEquals(1L, afterAllow.version());
        assertEquals(Set.of("read_file"), afterAllow.alwaysAllowedTools());

        // 「重启」一个 Store 读同一份持久化，always 仍在。
        SessionPermissionStore second = new SessionPermissionStore(persistence);
        assertTrue(second.isAlwaysAllowed("s1", "read_file"));
        assertTrue(second.isAskGranted("s1", "read_file", "随便什么参数"));

        // 其它变更也走 CAS 且不能把 always 弄丢。
        second.setMode("s1", PermissionMode.PLAN);
        second.setExecPolicyOverride("s1", ExecPolicy.ALLOW);
        assertEquals(3L, persistence.loadOrCreate("s1").version());
        assertEquals(Set.of("read_file"),
                persistence.loadOrCreate("s1").alwaysAllowedTools());

        // 撤销同样经过 CAS。
        second.revokeAlwaysAllow("s1", "read_file");
        assertEquals(4L, persistence.loadOrCreate("s1").version());
        assertTrue(persistence.loadOrCreate("s1").alwaysAllowedTools().isEmpty());
    }

    @Test
    void alwaysAllowIsIdempotentAndSessionScoped() {
        SessionPermissionStore store = new SessionPermissionStore();
        store.alwaysAllow("s1", "exec_command");
        store.alwaysAllow("s1", "exec_command");
        store.alwaysAllow("s1", " exec_command ");

        assertEquals(Set.of("exec_command"), store.alwaysAllowedTools("s1"));
        assertTrue(store.alwaysAllowedTools("s2").isEmpty(),
                "always 的作用域仅限本会话");
        assertFalse(store.isAlwaysAllowed("s2", "exec_command"));
    }

    private static final class InMemoryPersistence
            implements SessionPermissionPersistence {

        private final Map<String, State> states = new HashMap<>();

        @Override
        public synchronized State loadOrCreate(String sessionId) {
            return states.computeIfAbsent(sessionId, ignored -> new State(
                    "default", false, Set.of(), "dangerous", null, 0L));
        }

        @Override
        public synchronized boolean compareAndSet(
                String sessionId, State expected, State next) {
            State current = loadOrCreate(sessionId);
            if (!current.equals(expected)) {
                return false;
            }
            states.put(sessionId, next);
            return true;
        }

        @Override
        public synchronized void delete(String sessionId) {
            states.remove(sessionId);
        }
    }
}
