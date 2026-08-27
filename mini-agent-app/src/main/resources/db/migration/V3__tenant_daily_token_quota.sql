-- Atomic tenant-level daily token accounting used by the execution hard stop.

CREATE TABLE tenant_daily_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    token_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_daily_usage (tenant_id, usage_date),
    KEY idx_tenant_daily_usage_date (usage_date),
    CONSTRAINT fk_tenant_daily_usage_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
