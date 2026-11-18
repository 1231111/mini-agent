ALTER TABLE membership_orders
    ADD COLUMN expires_at DATETIME(6) NULL;

CREATE INDEX idx_mo_status_expiry ON membership_orders (status, expires_at);

UPDATE membership_orders
SET expires_at = DATE_ADD(created_at, INTERVAL 15 MINUTE)
WHERE expires_at IS NULL AND status = 'PENDING';
