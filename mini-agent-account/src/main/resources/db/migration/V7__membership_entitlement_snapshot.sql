-- Persist membership entitlements in users so the agent's quota authority can
-- enforce portal purchases immediately in the shared account/agent database.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS membership_daily_token_limit BIGINT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS membership_max_concurrent_tasks INT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS membership_expires_at DATETIME(6) NULL;

-- Preserve the entitlement of existing subscribers when this migration is deployed.
UPDATE users u
JOIN membership_subscriptions s
    ON s.id = (
        SELECT MAX(s2.id)
        FROM membership_subscriptions s2
        WHERE s2.user_id = u.id AND s2.status = 'ACTIVE'
    )
JOIN membership_plans p ON p.code = s.plan_code
SET u.membership_daily_token_limit = p.daily_token_limit,
    u.membership_max_concurrent_tasks = p.max_concurrent_tasks,
    u.membership_expires_at = s.expire_at;
