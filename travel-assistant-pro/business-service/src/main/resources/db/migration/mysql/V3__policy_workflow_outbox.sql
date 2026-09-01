ALTER TABLE travel_approval ADD COLUMN employee_level VARCHAR(32) NOT NULL DEFAULT 'L1';
ALTER TABLE travel_approval ADD COLUMN city_tier VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE travel_approval ADD COLUMN travel_type VARCHAR(32) NOT NULL DEFAULT 'DOMESTIC';
ALTER TABLE travel_approval ADD COLUMN policy_version INT NULL;
ALTER TABLE travel_approval ADD COLUMN policy_rule_id BIGINT NULL;
ALTER TABLE travel_approval ADD COLUMN policy_snapshot TEXT NULL;
ALTER TABLE travel_approval ADD COLUMN decision_trace TEXT NULL;
ALTER TABLE travel_approval ADD COLUMN requires_finance BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE travel_approval ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_approval_tenant_request ON travel_approval(tenant_id, request_id);

CREATE TABLE policy_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_policy_version_tenant UNIQUE (tenant_id, version_no)
);
CREATE INDEX idx_policy_version_effective ON policy_version(tenant_id, status, effective_from, effective_to);

CREATE TABLE travel_policy_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    policy_version INT NOT NULL,
    rule_name VARCHAR(160) NOT NULL,
    employee_level VARCHAR(32) NULL,
    city_tier VARCHAR(32) NULL,
    travel_type VARCHAR(32) NULL,
    max_budget DECIMAL(12, 2) NOT NULL,
    requires_finance BOOLEAN NOT NULL,
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_policy_rule_lookup ON travel_policy_rule(tenant_id, policy_version, enabled, priority);

CREATE TABLE outbox_event (
    id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processing_started_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_publish ON outbox_event(status, next_attempt_at, created_at);

CREATE TABLE processed_message (
    event_id VARCHAR(36) NOT NULL,
    consumer_name VARCHAR(80) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(event_id, consumer_name)
);

CREATE TABLE approval_event_projection (
    event_id VARCHAR(36) PRIMARY KEY,
    approval_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
