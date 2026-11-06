ALTER TABLE users ADD COLUMN memory_refresh_token_hash VARCHAR(64) NULL;
CREATE UNIQUE INDEX ux_users_memory_refresh_token_hash ON users (memory_refresh_token_hash);
