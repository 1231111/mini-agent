package com.miniagent.web;

import com.miniagent.agent.execution.ActionExecutionStatus;
import com.miniagent.agent.execution.ActionJournal;
import com.miniagent.agent.execution.ActionJournalEntry;
import com.miniagent.agent.execution.ActionJournalKey;
import com.miniagent.config.security.CurrentUser;
import com.miniagent.config.service.SystemAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SystemAdminControllerActionJournalTest {

    @Test
    void unresolvedReceiptViewIsBoundedAndContainsNoRawArguments() {
        ActionJournal journal = mock(ActionJournal.class);
        ActionJournalEntry entry = new ActionJournalEntry(
                new ActionJournalKey("receipt:" + "b".repeat(64), "opaque", "opaque", "opaque"),
                "exec_command", "a".repeat(64), ActionExecutionStatus.UNKNOWN, 1,
                123L, "OUTCOME_UNKNOWN", "private command text", "", "b".repeat(64), "c".repeat(64));
        org.mockito.Mockito.when(journal.unresolved(200, 0)).thenReturn(List.of(entry));
        SystemAdminController controller = new SystemAdminController();
        ReflectionTestUtils.setField(controller, "actionJournal", journal);
        ReflectionTestUtils.setField(controller, "service", mock(SystemAdminService.class));
        ReflectionTestUtils.setField(controller, "currentUser", mock(CurrentUser.class));

        var response = controller.unresolvedActions(10_000, -1);

        verify(journal).unresolved(200, 0);
        assertThat(response.isSuccess()).isTrue();
        var views = (List<?>) response.getData();
        assertThat(views).hasSize(1);
        var view = (SystemAdminController.UnresolvedActionView) views.get(0);
        assertThat(view.receiptId()).isEqualTo("b".repeat(64));
        assertThat(view.sessionIdHash()).isEqualTo("c".repeat(64));
        assertThat(view.status()).isEqualTo("UNKNOWN");
        assertThat(view.argumentsHash()).hasSize(64);
    }
}
