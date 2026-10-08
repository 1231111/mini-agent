package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPermissionStorePersistenceTest {

    @Test
    void restartAndOtherReplicaReadThePersistedPermissionState() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        SessionPermissionStore first =
                new SessionPermissionStore(persistence);

        first.setMode("s1", PermissionMode.PLAN);
        first.approvePlan("s1");
        first.setConfirmPolicy("s1", ConfirmPolicy.AUTO);
        first.setExecPolicyOverride("s1", ExecPolicy.ALLOW);
        first.grantAskTool("s1", "write_file");

        SessionPermissionStore second =
                new SessionPermissionStore(persistence);
        assertEquals(PermissionMode.PLAN, second.getMode("s1"));
        assertTrue(second.isPlanApproved("s1"));
        assertEquals(ConfirmPolicy.AUTO, second.getConfirmPolicy("s1"));
        assertEquals(
                ExecPolicy.ALLOW,
                second.getExecPolicyOverride("s1"));
        assertTrue(second.isAskGranted("s1", "write_file"));

        second.setExecPolicyOverride("s1", ExecPolicy.BLOCK);
        assertEquals(
                ExecPolicy.BLOCK,
                first.getExecPolicyOverride("s1"));
    }

    @Test
    void versionConflictReloadsBeforeApplyingMutation() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        SessionPermissionStore store =
                new SessionPermissionStore(persistence);
        store.get("s1");
        persistence.conflictNextWrite();

        store.setMode("s1", PermissionMode.ASK);

        assertEquals(PermissionMode.ASK, store.getMode("s1"));
    }

    private static final class InMemoryPersistence
            implements SessionPermissionPersistence {

        private final Map<String, State> states = new HashMap<>();
        private boolean conflictNextWrite;

        @Override
        public synchronized State loadOrCreate(String sessionId) {
            return states.computeIfAbsent(
                    sessionId,
                    ignored -> new State(
                            "default",
                            false,
                            Set.of(),
                            "dangerous",
                            null,
                            0L));
        }

        @Override
        public synchronized boolean compareAndSet(
                String sessionId, State expected, State next) {
            State current = loadOrCreate(sessionId);
            if (conflictNextWrite) {
                conflictNextWrite = false;
                states.put(
                        sessionId,
                        new State(
                                current.mode(),
                                current.planApproved(),
                                current.askGrantedTools(),
                                current.confirmPolicy(),
                                current.execPolicyOverride(),
                                current.version() + 1L));
                return false;
            }
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

        synchronized void conflictNextWrite() {
            conflictNextWrite = true;
        }
    }
}
