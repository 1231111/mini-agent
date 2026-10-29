package com.miniagent.agent.memory.manager;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.memory.procedure.governance")
public class ProcedureGovernanceProperties {
    private long minAttempts = 10;
    private double archiveMaxSuccessRate = 0.20;
    private boolean enabled = true;
    private boolean automaticAcceptanceEnabled = false;
    private int minimumIndependentValidations = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getMinAttempts() { return minAttempts; }
    public void setMinAttempts(long value) {
        if (value < 1) throw new IllegalArgumentException("min-attempts must be >= 1");
        minAttempts = value;
    }
    public double getArchiveMaxSuccessRate() { return archiveMaxSuccessRate; }
    public void setArchiveMaxSuccessRate(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("archive-max-success-rate must be in [0, 1]");
        archiveMaxSuccessRate = value;
    }
    public boolean isAutomaticAcceptanceEnabled() { return automaticAcceptanceEnabled; }
    public void setAutomaticAcceptanceEnabled(boolean value) { automaticAcceptanceEnabled = value; }
    public int getMinimumIndependentValidations() { return minimumIndependentValidations; }
    public void setMinimumIndependentValidations(int value) {
        if (value < 1) throw new IllegalArgumentException("minimum-independent-validations must be >= 1");
        minimumIndependentValidations = value;
    }
}
