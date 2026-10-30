ALTER TABLE memory_evolution_canary_observations
    ADD COLUMN variant VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' AFTER source_observation_id;

