package com.miniagent.agent.memory.manager;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.model.SemanticFact;

/** Keeps semantic-fact writes within the same limits as the persisted columns. */
public final class SemanticFactFieldValidator {
    public static final int SUBJECT_MAX_LENGTH = 512;
    public static final int PREDICATE_MAX_LENGTH = 256;
    public static final int OBJECT_MAX_LENGTH = 512;

    private SemanticFactFieldValidator() {}

    public static void validate(SemanticFact fact) {
        if (fact == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "语义事实不能为空");
        }
        validate(fact.getSubject(), fact.getPredicate(), fact.getObjectValue());
    }

    public static void validate(String subject, String predicate, String objectValue) {
        validateLength("subject", subject, SUBJECT_MAX_LENGTH);
        validateLength("predicate", predicate, PREDICATE_MAX_LENGTH);
        validateLength("objectValue", objectValue, OBJECT_MAX_LENGTH);
    }

    private static void validateLength(String field, String value, int maxLength) {
        if (value != null && value.codePointCount(0, value.length()) > maxLength) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID,
                    "语义事实字段 " + field + " 超过最大长度 " + maxLength + " 个字符，未写入");
        }
    }
}
