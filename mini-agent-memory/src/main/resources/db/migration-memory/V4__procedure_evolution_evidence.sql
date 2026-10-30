CREATE TABLE memory_evolution_evidence (
    evidence_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    procedure_id BIGINT NOT NULL,
    procedure_version INT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    task_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    failure_code VARCHAR(128),
    task_summary VARCHAR(500) NOT NULL,
    resolution_text VARCHAR(4000) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    proposal_id BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (evidence_id),
    CONSTRAINT ux_meve_failure UNIQUE (tenant_id, user_id, session_id, task_id, node_id, procedure_id, procedure_version),
    INDEX idx_meve_proposal (tenant_id, proposal_id)
);
