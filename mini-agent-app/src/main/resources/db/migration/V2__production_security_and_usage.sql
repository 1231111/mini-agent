-- Tenant isolation, revocable sessions, durable permissions and token accounting.

CREATE TABLE tenants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    daily_token_limit BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenants_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tenants (slug, name, enabled, daily_token_limit)
VALUES ('legacy', 'Legacy tenant', TRUE, 0);

ALTER TABLE users
    ADD COLUMN tenant_id BIGINT NULL,
    ADD COLUMN role VARCHAR(32) NULL,
    ADD COLUMN enabled BOOLEAN NULL;

UPDATE users
SET tenant_id = (SELECT id FROM tenants WHERE slug = 'legacy'),
    role = 'USER',
    enabled = TRUE
WHERE tenant_id IS NULL OR role IS NULL OR enabled IS NULL;

ALTER TABLE users
    MODIFY COLUMN tenant_id BIGINT NOT NULL,
    MODIFY COLUMN role VARCHAR(32) NOT NULL,
    MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD KEY idx_users_tenant (tenant_id),
    ADD CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

CREATE TABLE auth_sessions (
    token_hash VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at DATETIME(6) NOT NULL,
    client_fingerprint VARCHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    KEY idx_auth_session_user (user_id),
    KEY idx_auth_session_expiry (expires_at),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_session_permissions (
    session_id VARCHAR(100) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    plan_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ask_grants_json TEXT,
    confirm_policy VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_token_usage (
    session_id VARCHAR(100) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    llm_calls INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
