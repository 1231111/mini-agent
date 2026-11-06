ALTER TABLE agent_procedure_applications
    ADD COLUMN evolution_proposal_id BIGINT NULL;

ALTER TABLE agent_procedure_applications
    ADD COLUMN evolution_candidate_hash CHAR(64) NULL;

ALTER TABLE agent_procedure_applications
    ADD COLUMN procedure_variant VARCHAR(16) NOT NULL DEFAULT 'BASELINE';

DROP INDEX ux_apa_attempt ON agent_procedure_applications;
CREATE UNIQUE INDEX ux_apa_attempt ON agent_procedure_applications
    (tenant_id, user_id, session_id, task_id, node_id, procedure_id, procedure_version, procedure_variant);

CREATE INDEX idx_apa_evolution_observation
    ON agent_procedure_applications (tenant_id, evolution_proposal_id, procedure_variant, created_at);
