package com.miniagent.account.web;

import com.miniagent.account.service.MembershipUsageEventService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MembershipControllerUsageTest {

    @Test
    void forwardsOptionalEventIdAndKeepsLegacyFourFieldRequestValid() {
        MembershipUsageEventService service = mock(MembershipUsageEventService.class);
        MembershipController controller = new MembershipController();
        ReflectionTestUtils.setField(controller, "usageEvents", service);

        var withId = controller.usage(new MembershipController.UsageRequest(7L, 10L, 4L, 1, "event-1"));
        var withoutId = controller.usage(new MembershipController.UsageRequest(7L, 3L, 1L, 1));

        assertThat(withId.isSuccess()).isTrue();
        assertThat(withoutId.isSuccess()).isTrue();
        verify(service).recordUsage(7L, 10L, 4L, 1, "event-1");
        verify(service).recordUsage(7L, 3L, 1L, 1, null);
    }
}
