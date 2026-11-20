package com.miniagent.agent.memory.manager;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticFactFieldValidatorTest {

    @Test
    void acceptsSubjectAtThePersistedLimit() {
        assertDoesNotThrow(() -> SemanticFactFieldValidator.validate(
                "x".repeat(SemanticFactFieldValidator.SUBJECT_MAX_LENGTH), "learned", "value"));
    }

    @Test
    void rejectsSubjectOneCharacterOverThePersistedLimit() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> SemanticFactFieldValidator.validate(
                        "x".repeat(SemanticFactFieldValidator.SUBJECT_MAX_LENGTH + 1), "learned", "value"));

        assertEquals(ErrorCode.MEMORY_REQUEST_INVALID, error.getErrorCode());
    }
}
