CREATE TABLE memory_project_authorizations (
    tenant_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    authorization_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, project_id)
);

CREATE TABLE memory_project_members (
    tenant_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    authorization_version BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, project_id, user_id),
    CONSTRAINT fk_mpm_authorization FOREIGN KEY (tenant_id, project_id)
        REFERENCES memory_project_authorizations(tenant_id, project_id)
);

CREATE INDEX idx_mpm_user_project ON memory_project_members (tenant_id, user_id, project_id);
