package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionDeadlineTest {
    @Test void nestedBudgetCannotExtendItsParentAndScopeRestoresAfterFailure() {
        var parent = ToolExecutionDeadline.after(1, TimeUnit.SECONDS);
        assertNull(ToolExecutionDeadline.current());
        try (var ignored = ToolExecutionDeadline.bind(parent)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = ToolExecutionDeadline.bind(ToolExecutionDeadline.after(60, TimeUnit.SECONDS))) {
                    assertSame(parent, ToolExecutionDeadline.current());
                    throw new IllegalStateException("fixture");
                }
            });
            assertSame(parent, ToolExecutionDeadline.current());
        }
        assertNull(ToolExecutionDeadline.current());
    }

    @Test void exhaustedOrReservedTimeNeverBecomesAnUnlimitedSdkTimeout() {
        var deadline = ToolExecutionDeadline.after(50, TimeUnit.MILLISECONDS);
        assertThrows(ToolExecutionDeadline.Expired.class, () -> deadline.timeoutMillis(100));
        assertThrows(IllegalArgumentException.class, () -> ToolExecutionDeadline.after(0, TimeUnit.SECONDS));
    }
}
