package com.miniagent.account.web;

import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.service.MembershipService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MembershipControllerSimulatedPaymentTest {

    @Test
    void simulatedSettlementIsDisabledByDefault() {
        MembershipService service = mock(MembershipService.class);
        MembershipController controller = new MembershipController();
        ReflectionTestUtils.setField(controller, "membership", service);

        var response = controller.simulatePayment(
                new MembershipController.SimulatePaymentRequest(9L, "MA-order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        verifyNoInteractions(service);
    }

    @Test
    void enabledRouteForwardsAuthenticatedAccountIdentityAndOrderNumber() {
        MembershipService service = mock(MembershipService.class);
        MembershipController controller = new MembershipController();
        ReflectionTestUtils.setField(controller, "membership", service);
        ReflectionTestUtils.setField(controller, "simulatedPaymentEnabled", true);
        MembershipOrder paid = new MembershipOrder();
        paid.setId(3L);
        paid.setOrderNo("MA-order");
        paid.setUserId(9L);
        paid.setPlanCode("pro");
        paid.setStatus(MembershipOrder.Status.PAID);
        paid.setChannel("SIMULATED");
        when(service.simulatePayment(9L, "MA-order")).thenReturn(paid);

        var response = controller.simulatePayment(
                new MembershipController.SimulatePaymentRequest(9L, "MA-order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData().status()).isEqualTo("PAID");
        verify(service).simulatePayment(9L, "MA-order");
    }
}
