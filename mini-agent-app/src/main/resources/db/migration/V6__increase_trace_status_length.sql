-- Increase status column length in agent_trace_steps to accommodate longer status strings

ALTER TABLE agent_trace_steps MODIFY COLUMN status VARCHAR(50);