-- Cross-device sync for authenticated user-scoped semantic facts.
-- User ownership is taken from the account-issued memory token, never from the request body.
CREATE TABLE mem_user_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    subject VARCHAR(256) NOT NULL,
    predicate VARCHAR(256) NOT NULL,
    object_value TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_mem_user_fact_key UNIQUE (tenant_id, user_id, subject, predicate)
);
CREATE INDEX ix_mem_user_fact_owner ON mem_user_fact (tenant_id, user_id, id);
