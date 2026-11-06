CREATE TABLE memory_evolution_canary_assignments (
    assignment_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    task_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    procedure_id BIGINT NOT NULL,
    procedure_version INT NOT NULL,
    proposal_id BIGINT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    variant VARCHAR(16) NOT NULL,
    target_percent INT NOT NULL,
    bucket INT NOT NULL,
    router_attestation VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (assignment_id),
    CONSTRAINT ux_mevca_identity UNIQUE (tenant_id,user_id,session_id,task_id,node_id,procedure_id,proposal_id),
    INDEX idx_mevca_proposal (tenant_id,proposal_id,candidate_hash,variant)
);

