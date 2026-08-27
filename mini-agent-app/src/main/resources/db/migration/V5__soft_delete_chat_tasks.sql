-- Preserve task history for audit and recovery while hiding it from active APIs.
ALTER TABLE chat_tasks
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD KEY idx_chat_task_user_deleted_session_created
        (user_id, deleted, session_id, created_at);
