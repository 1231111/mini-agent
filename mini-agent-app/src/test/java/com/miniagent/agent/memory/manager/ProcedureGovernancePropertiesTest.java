package com.miniagent.agent.memory.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcedureGovernancePropertiesTest {

    @Test
    void rejectsInvalidAttemptThreshold() {
        ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setMinAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMinAttempts(-1));
    }

    @Test
    void automaticAcceptanceIsDisabledByDefaultAndValidationCountMustBePositive() {
        ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
        org.junit.jupiter.api.Assertions.assertFalse(properties.isAutomaticAcceptanceEnabled());
        assertThrows(IllegalArgumentException.class, () -> properties.setMinimumIndependentValidations(0));
    }

    @Test
    void rejectsInvalidSuccessRateThreshold() {
        ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setArchiveMaxSuccessRate(-0.01));
        assertThrows(IllegalArgumentException.class, () -> properties.setArchiveMaxSuccessRate(1.01));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setArchiveMaxSuccessRate(Double.NaN));
    }
}
