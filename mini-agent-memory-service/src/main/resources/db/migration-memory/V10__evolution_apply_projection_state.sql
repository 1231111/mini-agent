ALTER TABLE memory_evolution_proposals ADD COLUMN apply_operation VARCHAR(16);
ALTER TABLE memory_evolution_proposals ADD COLUMN apply_version INT;
ALTER TABLE memory_evolution_proposals ADD COLUMN apply_status VARCHAR(24);
ALTER TABLE memory_evolution_proposals ADD COLUMN apply_requested_at DATETIME(6);
ALTER TABLE memory_evolution_proposals ADD COLUMN apply_last_error VARCHAR(2000);

CREATE INDEX idx_mevp_apply_recovery
    ON memory_evolution_proposals (status, apply_status, apply_requested_at);
