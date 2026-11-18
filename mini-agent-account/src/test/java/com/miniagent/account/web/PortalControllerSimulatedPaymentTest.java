package com.miniagent.account.web;

import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.account.service.AccountAuthService;
import com.miniagent.account.service.DesktopLoginTicketStore;
import com.miniagent.account.service.MembershipService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PortalControllerSimulatedPaymentTest {

    @Test
    void portalUsesSessionIdentityAndAllowsOnlyTheServiceToSettleOwnedOrder() {
        MembershipService membership = mock(MembershipService.class);
        UserRepository users = mock(UserRepository.class);
        PortalController controller = controller(membership, users, true);
        User user = new User();
        user.setId(24L);
        when(users.findById(24L)).thenReturn(Optional.of(user));
        MembershipOrder paid = new MembershipOrder();
        paid.setId(2L);
        paid.setOrderNo("MA-order");
        paid.setUserId(24L);
        paid.setPlanCode("pro");
        paid.setStatus(MembershipOrder.Status.PAID);
        when(membership.simulatePayment(24L, "MA-order")).thenReturn(paid);
        MockHttpServletRequest request = loggedInRequest(24L);

        var response = controller.simulatePayment("MA-order", request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().status()).isEqualTo("PAID");
        verify(membership).simulatePayment(24L, "MA-order");
    }

    @Test
    void portalDoesNotSettleOrdersWhenFeatureIsDisabled() {
        MembershipService membership = mock(MembershipService.class);
        UserRepository users = mock(UserRepository.class);
        PortalController controller = controller(membership, users, false);
        User user = new User();
        user.setId(24L);
        when(users.findById(24L)).thenReturn(Optional.of(user));

        var response = controller.simulatePayment("MA-order", loggedInRequest(24L));

        assertThat(response.isSuccess()).isFalse();
        verifyNoInteractions(membership);
    }

    @Test
    void portalCancelsOrderUsingSessionUserId() {
        MembershipService membership = mock(MembershipService.class);
        UserRepository users = mock(UserRepository.class);
        PortalController controller = controller(membership, users, true);
        User user = new User();
        user.setId(24L);
        when(users.findById(24L)).thenReturn(Optional.of(user));
        MembershipOrder canceled = new MembershipOrder();
        canceled.setOrderNo("MA-order");
        canceled.setUserId(24L);
        canceled.setPlanCode("pro");
        canceled.setStatus(MembershipOrder.Status.CANCELED);
        when(membership.cancelOrder(24L, "MA-order")).thenReturn(canceled);

        var response = controller.cancelOrder("MA-order", loggedInRequest(24L));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().status()).isEqualTo("CANCELED");
        verify(membership).cancelOrder(24L, "MA-order");
    }

    private static PortalController controller(MembershipService membership,
                                               UserRepository users,
                                               boolean simulatedPaymentEnabled) {
        PortalController controller = new PortalController();
        ReflectionTestUtils.setField(controller, "membership", membership);
        ReflectionTestUtils.setField(controller, "users", users);
        ReflectionTestUtils.setField(controller, "auth", mock(AccountAuthService.class));
        ReflectionTestUtils.setField(controller, "tickets", mock(DesktopLoginTicketStore.class));
        ReflectionTestUtils.setField(controller, "simulatedPaymentEnabled", simulatedPaymentEnabled);
        return controller;
    }

    private static MockHttpServletRequest loggedInRequest(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(PortalController.SESSION_USER_ID, userId);
        return request;
    }
}
