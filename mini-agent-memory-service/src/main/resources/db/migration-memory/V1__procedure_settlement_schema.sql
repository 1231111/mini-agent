-- 独立记忆服务的最小 schema：只覆盖 Procedure 投影与结算这一条纵向切片。
--
-- 边界说明：
--   * 不与 mini-agent-app 共用数据库。执行侧的库有自己的 Flyway 历史（V1..V15），
--     那里的 agent_procedures 由实体和 Java 迁移共同维护，列的顺序、索引名都不同。
--     把两套迁移指向同一个库，第二次执行必然撞表。
--   * id 不由本库分配：它是执行侧的 procedure id，投影必须沿用同一个值，
--     否则结算请求里的 procedureId 在这里找不到行。
--   * created_at / updated_at 用 DATETIME(6)：TIMESTAMP 在 MySQL 上有 2038 上限，
--     而记忆服务的生命周期不该被这个上限绑住。

CREATE TABLE agent_procedures (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    preconditions_json TEXT,
    steps_json TEXT NOT NULL,
    success_conditions_json TEXT,
    usage_count INT DEFAULT 0,
    success_rate DOUBLE DEFAULT 0.0,
    procedure_version INT NOT NULL DEFAULT 1,
    success_count BIGINT NOT NULL DEFAULT 0,
    attempt_count BIGINT NOT NULL DEFAULT 0,
    importance DOUBLE DEFAULT 0.5,
    status VARCHAR(32),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_ap_scope_memsvc ON agent_procedures (scope_type, scope_id);
CREATE INDEX idx_ap_name_memsvc ON agent_procedures (name);

CREATE TABLE agent_procedure_applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    task_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    procedure_id BIGINT NOT NULL,
    procedure_version INT NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    result VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

-- 幂等的根据：同一个会话的同一个节点、针对同一版 SOP，只能结算一次。
CREATE UNIQUE INDEX ux_apa_attempt ON agent_procedure_applications
    (tenant_id, user_id, session_id, task_id, node_id, procedure_id, procedure_version);
CREATE INDEX idx_apa_procedure ON agent_procedure_applications
    (tenant_id, user_id, procedure_id, procedure_version);
