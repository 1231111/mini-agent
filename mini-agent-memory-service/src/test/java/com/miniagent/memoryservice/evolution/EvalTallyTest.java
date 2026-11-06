package com.miniagent.memoryservice.evolution;

import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.CaseVerdict;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalTallyTest {

    @Test
    void missingOrForeignVerdictsCannotInflateTheScore() {
        List<EvalCase> issued = List.of(trigger("T:1"), trigger("T:2"), trigger("T:3"),
                new EvalCase("S:1", CaseKind.SUCCESS, "m", null));
        List<CaseVerdict> verdicts = List.of(
                new CaseVerdict("T:1", Verdict.WIN, true, true, null),
                new CaseVerdict("T:2", Verdict.LOSS, true, false, null),
                new CaseVerdict("T:9", Verdict.WIN, true, true, "未下发的案例"),
                new CaseVerdict("S:1", Verdict.TIE, true, true, null));

        EvalCoordinator.Tally t = EvalCoordinator.tally(issued, verdicts);

        assertEquals(1, t.count(CaseKind.TRIGGER, Verdict.ERROR), "漏交的 T:3 记为判错");
        assertEquals(2, t.judged(CaseKind.TRIGGER));
        assertEquals(0.5, t.rate(CaseKind.TRIGGER, Verdict.WIN), "T:9 不计入");
        assertEquals(1, t.candidateCompileFailures());
        assertEquals(4, t.all());
        assertEquals(1, t.errors());
    }

    private static EvalCase trigger(String id) {
        return new EvalCase(id, CaseKind.TRIGGER, "m", "f");
    }
}
