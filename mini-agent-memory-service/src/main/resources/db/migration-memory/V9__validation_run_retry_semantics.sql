ALTER TABLE memory_evolution_validation_runs
    DROP INDEX ux_mevr_proposal_suite;

CREATE INDEX idx_mevr_suite
    ON memory_evolution_validation_runs (tenant_id, proposal_id, candidate_hash, suite_id, suite_version, started_at);

CREATE TABLE memory_evolution_validation_locks (
    tenant_id VARCHAR(64) NOT NULL,
    proposal_id BIGINT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    suite_id VARCHAR(128) NOT NULL,
    suite_version VARCHAR(128) NOT NULL,
    owner_run_id VARCHAR(128),
    lease_until DATETIME(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, proposal_id, candidate_hash, suite_id, suite_version),
    UNIQUE KEY ux_mevl_owner (owner_run_id),
    INDEX idx_mevl_lease (lease_until)
);
