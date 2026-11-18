-- Optional idempotency key for client usage reports. NULL keeps old clients append-only.
ALTER TABLE membership_usage_events ADD COLUMN event_id VARCHAR(128) NULL;
CREATE UNIQUE INDEX uk_usage_user_event ON membership_usage_events (user_id, event_id);
