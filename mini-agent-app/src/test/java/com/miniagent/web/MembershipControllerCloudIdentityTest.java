package com.miniagent.web;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.config.cloud.CloudMembershipClient;
import com.miniagent.config.cloud.CloudMembershipClient.MembershipView;
import com.miniagent.config.cloud.CloudMembershipClient.OrderView;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.CurrentUser;
import com.miniagent.config.service.UserTokenQuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MembershipControllerCloudIdentityTest {

    private MembershipController controller;
    private CloudMembershipClient membership;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        controller = new MembershipController();
        membership = mock(CloudMembershipClient.class);
        users = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "membership", membership);
        ReflectionTestUtils.setField(controller, "users", users);
        ReflectionTestUtils.setField(controller, "currentUser", new CurrentUser());
        authenticate(7L);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void desktopQueriesAndOrdersUseCurrentUsersCloudMappingInsteadOfLocalPrimaryKey() {
        when(users.findExternalIdById(7L)).thenReturn(Optional.of(" 42001 "));
        MembershipView view = new MembershipView("PRO", "专业版", 1000L, 2, null, null);
        OrderView order = new OrderView(13L, "test-order", 42001L, "PRO", 200L,
                "CNY", "PENDING", null, null, null, null);
        List<OrderView> pending = List.of(order);
        when(membership.current(42001L)).thenReturn(view);
        when(membership.createOrder(42001L, "PRO")).thenReturn(order);
        when(membership.pendingOrders(42001L)).thenReturn(pending);
        when(membership.cancelOrder(42001L, "test-order")).thenReturn(order);

        assertSame(view, controller.current().getData());
        assertSame(order, controller.createOrder(new MembershipController.CreateOrderRequest(" PRO ")).getData());
        assertSame(pending, controller.pendingOrders().getData());
        assertSame(order, controller.cancelOrder("test-order").getData());

        verify(users, times(4)).findExternalIdById(7L);
        verify(membership).current(42001L);
        verify(membership).createOrder(42001L, "PRO");
        verify(membership).pendingOrders(42001L);
        verify(membership).cancelOrder(42001L, "test-order");
        verifyNoMoreInteractions(users, membership);
    }

    @Test
    void desktopMissingOrInvalidMappingFailsBeforeAnyCloudCallWithoutLocalIdOrUsernameFallback() {
        for (String externalId : Arrays.asList(null, "", " \t ", "not-numeric", "0", "-1",
                "9223372036854775808", "42.5")) {
            reset(users, membership);
            when(users.findExternalIdById(7L)).thenReturn(Optional.ofNullable(externalId));

            BusinessException current = assertThrows(BusinessException.class, () -> controller.current());
            BusinessException order = assertThrows(BusinessException.class, () -> controller.createOrder(
                    new MembershipController.CreateOrderRequest("PRO")));

            assertEquals(ErrorCode.AUTH_FORBIDDEN, current.getErrorCode());
            assertEquals(ErrorCode.AUTH_FORBIDDEN, order.getErrorCode());
            assertEquals("当前账号未关联有效的云端身份，请重新登录", current.getMessage());
            verify(users, times(2)).findExternalIdById(7L);
            verifyNoMoreInteractions(users);
            verifyNoInteractions(membership);
        }
    }

    @Test
    void identityMappingIsResolvedForCurrentSessionRatherThanCachedAcrossUsers() {
        when(users.findExternalIdById(7L)).thenReturn(Optional.of("42001"));
        when(users.findExternalIdById(8L)).thenReturn(Optional.of("42002"));
        controller.current();
        authenticate(8L);
        controller.createOrder(new MembershipController.CreateOrderRequest("PRO"));

        verify(users).findExternalIdById(7L);
        verify(users).findExternalIdById(8L);
        verify(membership).current(42001L);
        verify(membership).createOrder(42002L, "PRO");
        verifyNoMoreInteractions(users, membership);
    }

    @Test
    void sharedDatabaseUsesValidatedSessionPrimaryKeyWithoutShadowMapping() {
        ReflectionTestUtils.setField(controller, "sharedDatabase", true);

        assertTrue(controller.current().isSuccess());
        assertTrue(controller.createOrder(new MembershipController.CreateOrderRequest("PRO")).isSuccess());

        verify(membership).current(7L);
        verify(membership).createOrder(7L, "PRO");
        verifyNoMoreInteractions(membership);
        verifyNoInteractions(users);
    }

    @Test
    void simulatedPaymentUsesMappedCloudIdentityAndSynchronizesThePurchasedAllowance() {
        UserTokenQuotaService quota = mock(UserTokenQuotaService.class);
        ReflectionTestUtils.setField(controller, "tokenQuota", quota);
        ReflectionTestUtils.setField(controller, "simulatedPaymentEnabled", true);
        ReflectionTestUtils.setField(controller, "sharedDatabase", false);
        when(users.findExternalIdById(7L)).thenReturn(Optional.of("42001"));
        OrderView order = new OrderView(13L, "test-order", 42001L, "PRO", 200L,
                "CNY", "PAID", "SIMULATED", "SIMULATED-test-order", LocalDateTime.now(), null);
        MembershipView view = new MembershipView("PRO", "专业版", 25_000L, 2,
                LocalDateTime.now().plusDays(30), "test-order");
        when(membership.simulatePayment(42001L, "test-order")).thenReturn(order);
        when(membership.current(42001L)).thenReturn(view);

        var response = controller.simulatePayment("test-order");

        assertTrue(response.isSuccess());
        verify(membership).simulatePayment(42001L, "test-order");
        verify(membership).current(42001L);
        verify(quota).synchronizeMembership(7L, 25_000L, 2, view.expireAt());
        verify(users).findExternalIdById(7L);
        verifyNoMoreInteractions(membership);
    }

    @Test
    void simulatedPaymentCannotBeUsedUntilExplicitlyEnabled() {
        when(users.findExternalIdById(7L)).thenReturn(Optional.of("42001"));

        assertThrows(BusinessException.class, () -> controller.simulatePayment("test-order"));

        verify(users, never()).findExternalIdById(7L);
        verifyNoInteractions(membership);
    }

    @Test
    void unauthenticatedCallsCannotResolveOrForwardAnyIdentityInEitherMode() {
        SecurityContextHolder.clearContext();
        for (boolean sharedDatabase : List.of(false, true)) {
            ReflectionTestUtils.setField(controller, "sharedDatabase", sharedDatabase);
            BusinessException current = assertThrows(BusinessException.class, () -> controller.current());
            BusinessException order = assertThrows(BusinessException.class, () -> controller.createOrder(
                    new MembershipController.CreateOrderRequest("PRO")));
            assertEquals(ErrorCode.AUTH_NOT_AUTHENTICATED, current.getErrorCode());
            assertEquals(ErrorCode.AUTH_NOT_AUTHENTICATED, order.getErrorCode());
        }
        verifyNoInteractions(users, membership);
    }

    @Test
    void invalidSessionPrimaryKeyCannotBeUsedForCloudAccessInEitherMode() {
        for (boolean sharedDatabase : List.of(false, true)) {
            ReflectionTestUtils.setField(controller, "sharedDatabase", sharedDatabase);
            for (Long localId : Arrays.asList(null, 0L, -1L)) {
                authenticate(localId);
                BusinessException current = assertThrows(BusinessException.class, () -> controller.current());
                BusinessException order = assertThrows(BusinessException.class, () -> controller.createOrder(
                        new MembershipController.CreateOrderRequest("PRO")));
                assertEquals(ErrorCode.AUTH_NOT_AUTHENTICATED, current.getErrorCode());
                assertEquals(ErrorCode.AUTH_NOT_AUTHENTICATED, order.getErrorCode());
            }
        }
        verifyNoInteractions(users, membership);
    }

    @Test
    void missingPlanCodeRemainsALocalValidationFailureWithoutCloudCalls() {
        for (MembershipController.CreateOrderRequest request : Arrays.asList(null,
                new MembershipController.CreateOrderRequest(null),
                new MembershipController.CreateOrderRequest(" \t "))) {
            var response = controller.createOrder(request);
            assertFalse(response.isSuccess());
            assertEquals(ErrorCode.MEMBER_PLAN_NOT_FOUND.getCode(), response.getCode());
        }
        verifyNoInteractions(users, membership);
    }

    private void authenticate(Long localId) {
        var user = new AuthenticatedUser(localId, 1L, "local-shadow-name", UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
