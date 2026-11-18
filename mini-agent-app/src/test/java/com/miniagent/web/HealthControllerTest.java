package com.miniagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.planner.PlannerMetrics;
import com.miniagent.agent.planner.PlannerProperties;
import com.miniagent.agent.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void unavailableStateStoreReturns503WithoutExposingExceptionDetails() throws Exception {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.activeSessionCount()).thenThrow(
                new IllegalStateException("jdbc://internal-db password=super-secret"));
        HealthController controller = controller(stateStore);

        var response = controller.healthCheck();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<?, ?> body = response.getBody();
        assertEquals("DOWN", body.get("status"));
        Map<?, ?> components = (Map<?, ?>) body.get("components");
        Map<?, ?> store = (Map<?, ?>) components.get("stateStore");
        assertEquals("state store unavailable", store.get("error"));
        String json = new ObjectMapper().writeValueAsString(body);
        assertFalse(json.contains("internal-db"));
        assertFalse(json.contains("super-secret"));
    }

    @Test
    void availableStateStoreReturns200AndUpStatus() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.activeSessionCount()).thenReturn(3);
        HealthController controller = controller(stateStore);

        var response = controller.healthCheck();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertTrue(response.getBody().containsKey("metrics"));
    }

    private static HealthController controller(PlannerStateStore stateStore) {
        HealthController controller = new HealthController();
        ReflectionTestUtils.setField(controller, "metrics", new PlannerMetrics());
        ReflectionTestUtils.setField(controller, "properties", new PlannerProperties());
        ReflectionTestUtils.setField(controller, "stateStore", stateStore);
        return controller;
    }
}
