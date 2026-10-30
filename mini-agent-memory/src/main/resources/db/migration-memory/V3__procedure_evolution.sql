CREATE TABLE memory_evolution_proposals (
    proposal_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    procedure_id BIGINT NOT NULL,
    base_version INT NOT NULL,
    candidate_id VARCHAR(128) NOT NULL,
    base_content_json LONGTEXT NOT NULL,
    candidate_content_json LONGTEXT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    generator_version VARCHAR(128) NOT NULL,
    evidence_ids_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    baseline_samples INT NOT NULL DEFAULT 0,
    candidate_samples INT NOT NULL DEFAULT 0,
    candidate_version INT,
    baseline_failure_rate DOUBLE,
    candidate_failure_rate DOUBLE,
    decision_reason VARCHAR(2000),
    canary_target_percent INT,
    canary_started_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (proposal_id),
    CONSTRAINT ux_mevp_candidate UNIQUE (tenant_id, procedure_id, candidate_id),
    INDEX idx_mevp_work (status, updated_at),
    INDEX idx_mevp_owner (tenant_id, user_id, created_at)
);

CREATE TABLE memory_evolution_trials (
    trial_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    proposal_id BIGINT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    suite_id VARCHAR(128) NOT NULL,
    suite_version VARCHAR(128) NOT NULL,
    sample_id VARCHAR(128) NOT NULL,
    variant VARCHAR(16) NOT NULL,
    passed BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (trial_id),
    CONSTRAINT ux_mevt_sample UNIQUE (tenant_id, proposal_id, candidate_hash, suite_id, suite_version, sample_id, variant),
    INDEX idx_mevt_summary (tenant_id, proposal_id, candidate_hash, suite_id, suite_version)
);

CREATE TABLE memory_evolution_canary_observations (
    observation_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    proposal_id BIGINT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    source_observation_id VARCHAR(128) NOT NULL,
    passed BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (observation_id),
    CONSTRAINT ux_mevco_source UNIQUE (tenant_id, proposal_id, source_observation_id),
    INDEX idx_mevco_summary (tenant_id, proposal_id, candidate_hash)
);

