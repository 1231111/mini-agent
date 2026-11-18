-- A persistent, privacy-minimal lifecycle lock prevents late app outbox replay from
-- recreating a user's facts or USER-scoped procedures after a completed erasure.
-- subject_hash is SHA-256(tenant_id || NUL || user_id); the raw user id is not retained.
CREATE TABLE mem_subject_erasure (
    tenant_id VARCHAR(64) NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    erased_items INT NOT NULL DEFAULT 0,
    erased_facts INT NOT NULL DEFAULT 0,
    erased_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, subject_hash),
    INDEX ix_mem_subject_erasure_status (status, updated_at)
);
