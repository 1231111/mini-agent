-- 进化 v2：记忆服务成为 SOP 的唯一真相源（不可变修订 + 激活指针 + 激活历史），
-- 结果事件、作业队列、提案、回归集、租户策略、预算与审计。
-- V1..V10 的 v1 表保留不动（历史数据），v2 代码不再读写它们。
-- 「每条目至多一个未终结提案」「每个去重键至多一个未完成作业」用应用维护的可空列 + 唯一索引表达，
-- 不用生成列，保证 H2(MySQL 模式) 与 MySQL 8.0 同一份 SQL。
-- holdout_revision_id / holdout_period：留存组看到的修订 =「本周期开始起不做任何进化激活」时的修订，
-- 每次激活时按周期号维护，读路径不必回放激活历史。

CREATE TABLE mem_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(128) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    name_key VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_revision_id BIGINT NULL,
    holdout_revision_id BIGINT NULL,
    holdout_period INT NULL,
    activation_version BIGINT NOT NULL,
    evolution_locked BOOLEAN NOT NULL DEFAULT FALSE,
    health VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    legacy_procedure_id BIGINT NULL,
    validated_env_version VARCHAR(128) NULL,
    last_used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_mem_item_name UNIQUE (tenant_id, scope_type, scope_id, name_key),
    CONSTRAINT ux_mem_item_legacy UNIQUE (tenant_id, legacy_procedure_id)
);
CREATE INDEX ix_mem_item_scope ON mem_item (tenant_id, scope_type, scope_id, status);

CREATE TABLE mem_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    parent_revision_id BIGINT NULL,
    origin VARCHAR(16) NOT NULL,
    content_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    proposal_id BIGINT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_mem_revision_no UNIQUE (item_id, revision_no)
);

CREATE TABLE mem_activation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    from_revision_id BIGINT NULL,
    to_revision_id BIGINT NOT NULL,
    reason VARCHAR(24) NOT NULL,
    proposal_id BIGINT NULL,
    actor VARCHAR(64) NOT NULL,
    activation_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_mem_activation_item ON mem_activation (item_id, id);
CREATE INDEX ix_mem_activation_time ON mem_activation (created_at);

CREATE TABLE outcome_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id VARCHAR(128) NULL,
    item_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    proposal_id BIGINT NULL,
    arm VARCHAR(16) NULL,
    arm_valid BOOLEAN NOT NULL,
    holdout BOOLEAN NOT NULL,
    task_key VARCHAR(255) NOT NULL,
    node_key VARCHAR(255) NOT NULL,
    result VARCHAR(16) NOT NULL,
    counted BOOLEAN NOT NULL,
    error_code VARCHAR(64) NULL,
    fingerprint CHAR(16) NULL,
    detail_json TEXT NULL,
    env_version VARCHAR(128) NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_outcome_event UNIQUE (event_id)
);
CREATE INDEX ix_outcome_item_time ON outcome_event (item_id, occurred_at);
CREATE INDEX ix_outcome_revision_time ON outcome_event (revision_id, occurred_at);
CREATE INDEX ix_outcome_proposal ON outcome_event (proposal_id, arm);
CREATE INDEX ix_outcome_task ON outcome_event (tenant_id, task_key);
CREATE INDEX ix_outcome_user ON outcome_event (tenant_id, user_id);
CREATE INDEX ix_outcome_received ON outcome_event (received_at);

CREATE TABLE evo_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_type VARCHAR(24) NOT NULL,
    open_key VARCHAR(191) NULL,
    tenant_id VARCHAR(64) NULL,
    item_id BIGINT NULL,
    proposal_id BIGINT NULL,
    payload_json TEXT NULL,
    status VARCHAR(16) NOT NULL,
    priority INT NOT NULL,
    attempts INT NOT NULL,
    run_after DATETIME(6) NOT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_token VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_evo_job_open UNIQUE (open_key)
);
CREATE INDEX ix_evo_job_ready ON evo_job (job_type, status, priority, run_after);

CREATE TABLE evo_proposal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    item_id BIGINT NOT NULL,
    open_item_id BIGINT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    fingerprint CHAR(16) NULL,
    state VARCHAR(24) NOT NULL,
    state_reason VARCHAR(255) NULL,
    base_revision_id BIGINT NOT NULL,
    candidate_revision_id BIGINT NULL,
    candidate_hash CHAR(64) NULL,
    generator_version VARCHAR(32) NOT NULL,
    evidence_json TEXT NULL,
    rationale TEXT NULL,
    eval_json TEXT NULL,
    offline_passed BOOLEAN NULL,
    experiment_salt VARCHAR(64) NULL,
    traffic_bps INT NOT NULL,
    max_traffic_bps INT NOT NULL,
    ramp_step INT NOT NULL,
    step_started_at DATETIME(6) NULL,
    experiment_started_at DATETIME(6) NULL,
    promoted_at DATETIME(6) NULL,
    monitor_until DATETIME(6) NULL,
    decision_json TEXT NULL,
    reviewer VARCHAR(64) NULL,
    review_note VARCHAR(1000) NULL,
    tokens_used BIGINT NOT NULL,
    expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_evo_proposal_open UNIQUE (open_item_id)
);
CREATE INDEX ix_evo_proposal_state ON evo_proposal (state, updated_at);
CREATE INDEX ix_evo_proposal_item ON evo_proposal (item_id, id);
CREATE INDEX ix_evo_proposal_tenant ON evo_proposal (tenant_id, state);

CREATE TABLE evo_regression_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    fingerprint CHAR(16) NULL,
    user_message TEXT NOT NULL,
    failure_reason VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_evo_regression_item ON evo_regression_case (item_id, status, id);

CREATE TABLE evo_tenant_policy (
    tenant_id VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NULL,
    frozen BOOLEAN NOT NULL,
    frozen_reason VARCHAR(255) NULL,
    daily_token_budget BIGINT NULL,
    holdout_bps INT NULL,
    updated_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id)
);

CREATE TABLE evo_budget_usage (
    tenant_id VARCHAR(64) NOT NULL,
    usage_date DATE NOT NULL,
    tokens BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, usage_date)
);

CREATE TABLE evo_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    proposal_id BIGINT NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    agree BOOLEAN NOT NULL,
    note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_evo_review_proposal UNIQUE (proposal_id)
);

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NULL,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(48) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_id VARCHAR(64) NULL,
    detail_json TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_audit_tenant ON audit_log (tenant_id, id);
