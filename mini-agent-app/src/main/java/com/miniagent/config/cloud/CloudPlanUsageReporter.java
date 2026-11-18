package com.miniagent.config.cloud;

import com.miniagent.agent.core.TokenUsageTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * Registers the durable outbox as the completed-model-call usage listener.
 */
@Component
public class CloudPlanUsageReporter {

    @Autowired
    private MembershipUsageOutboxService outbox;

    @PostConstruct
    void bind() {
        TokenUsageTracker.setEventListener(outbox::enqueue);
    }

    @PreDestroy
    void unbind() {
        TokenUsageTracker.setEventListener(null);
    }
}
