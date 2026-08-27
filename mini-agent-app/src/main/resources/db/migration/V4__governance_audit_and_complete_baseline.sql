-- Complete legacy tables that were previously created only by Hibernate, then add
-- production governance, encrypted-secret capacity and soft-deleted session ownership.

CREATE TABLE IF NOT EXISTS agent_trace_steps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(100) NOT NULL,
    execution_id VARCHAR(60) NOT NULL,
    parent_execution_id VARCHAR(60),
    parent_step_id BIGINT,
    user_question TEXT,
    turn_index INT NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    tool_name VARCHAR(100),
    tool_args TEXT,
    content LONGTEXT,
    sub_goal_text TEXT,
    sub_goal_done INT NOT NULL DEFAULT 0,
    sub_goal_total INT NOT NULL DEFAULT 0,
    status VARCHAR(20),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    token_input BIGINT,
    token_output BIGINT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_trace_session_turn (session_id, turn_index),
    KEY idx_trace_execution (execution_id),
    KEY idx_trace_parent_step (parent_step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_rule_set (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    source VARCHAR(40) NOT NULL,
    note VARCHAR(500),
    config_json TEXT,
    created_at DATETIME(6),
    activated_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_intent_rule_set_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_set_id BIGINT NOT NULL,
    signal_group VARCHAR(40) NOT NULL,
    pattern TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    priority INT NOT NULL,
    note VARCHAR(500),
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_intent_rule_set (rule_set_id),
    KEY idx_intent_rule_group (signal_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_tool_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_set_id BIGINT NOT NULL,
    profile VARCHAR(20) NOT NULL,
    tools_json TEXT,
    PRIMARY KEY (id),
    KEY idx_intent_tool_profile_set (rule_set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_rule_hit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id VARCHAR(60),
    session_id VARCHAR(100),
    rule_set_id BIGINT,
    layer VARCHAR(10) NOT NULL,
    intent VARCHAR(40) NOT NULL,
    reason VARCHAR(200),
    user_text TEXT,
    matched_signals TEXT,
    plan_json TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_intent_hit_exec (execution_id),
    KEY idx_intent_hit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_rule_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id VARCHAR(60),
    session_id VARCHAR(100),
    predicted_intent VARCHAR(40),
    correct_intent VARCHAR(40) NOT NULL,
    feedback_type VARCHAR(40) NOT NULL,
    note TEXT,
    user_text TEXT,
    created_by BIGINT,
    status VARCHAR(20) NOT NULL,
    proposal_id BIGINT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_intent_fb_exec (execution_id),
    KEY idx_intent_fb_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS intent_rule_proposal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    feedback_id BIGINT,
    status VARCHAR(20) NOT NULL,
    signal_group VARCHAR(40) NOT NULL,
    pattern TEXT NOT NULL,
    rationale TEXT,
    reviewed_by BIGINT,
    reviewed_at DATETIME(6),
    published_rule_set_id BIGINT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_intent_proposal_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE chat_conversations
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD KEY idx_chat_conversation_user_deleted_updated (user_id, deleted, updated_at);

ALTER TABLE user_model_config
    MODIFY COLUMN custom_api_key VARCHAR(1024) NULL;

CREATE TABLE admin_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT,
    request_id VARCHAR(80),
    details_json TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_admin_audit_actor_created (actor_user_id, created_at),
    KEY idx_admin_audit_target (target_type, target_id),
    KEY idx_admin_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
