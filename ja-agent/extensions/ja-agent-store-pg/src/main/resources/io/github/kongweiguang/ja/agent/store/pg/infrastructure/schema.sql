-- @author kongweiguang
-- SPDX-License-Identifier: GPL-3.0-or-later

CREATE TABLE IF NOT EXISTS agent_schema_version (
    singleton_id INTEGER NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    CONSTRAINT pk_agent_schema_version PRIMARY KEY (singleton_id),
    CONSTRAINT ck_agent_schema_singleton CHECK (singleton_id = 1)
);

INSERT INTO agent_schema_version(singleton_id, schema_version)
VALUES (1, 1)
ON CONFLICT (singleton_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS agent_conversation (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    event_sequence BIGINT NOT NULL CHECK (event_sequence >= 0),
    record_sequence BIGINT NOT NULL CHECK (record_sequence >= 0),
    latest_execution_sequence BIGINT NOT NULL CHECK (latest_execution_sequence >= 0),
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_conversation PRIMARY KEY (tenant_id, thread_id),
    CONSTRAINT uk_agent_conversation_owner UNIQUE (tenant_id, thread_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_execution (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND turn_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    admission_sequence BIGINT NOT NULL CHECK (admission_sequence > 0),
    state VARCHAR(128) NOT NULL CONSTRAINT ck_agent_execution_state CHECK (state IN ('QUEUED','RUNNING','WAITING_APPROVAL','WAITING_INPUT','SUSPENDED','COMPLETED','FAILED','CANCELLED')),
    mutation_version BIGINT NOT NULL CHECK (mutation_version >= 0),
    cursor_schema_version INTEGER NOT NULL CHECK (cursor_schema_version > 0),
    cursor_json TEXT NOT NULL CONSTRAINT ck_agent_execution_cursor_json CHECK (
        jsonb_typeof(cursor_json::jsonb) = 'object'
        AND octet_length(cursor_json) <= 16777216
    ),
    last_record_sequence BIGINT NOT NULL CHECK (last_record_sequence >= 0),
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_execution PRIMARY KEY (tenant_id, thread_id, turn_id, user_id),
    CONSTRAINT uk_agent_execution_admission UNIQUE (tenant_id, thread_id, admission_sequence),
    CONSTRAINT fk_agent_execution_conversation FOREIGN KEY (tenant_id, thread_id, user_id)
        REFERENCES agent_conversation (tenant_id, thread_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_record (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND turn_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    record_id VARCHAR(256) NOT NULL CHECK (length(record_id) BETWEEN 1 AND 256 AND record_id !~ '^[[:space:]]*$'),
    record_type VARCHAR(256) NOT NULL CHECK (length(record_type) BETWEEN 1 AND 256 AND record_type !~ '^[[:space:]]*$'),
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version > 0),
    record_sequence BIGINT NOT NULL CHECK (record_sequence > 0),
    payload_json TEXT NOT NULL CONSTRAINT ck_agent_record_payload_json CHECK (
        jsonb_typeof(payload_json::jsonb) = 'object'
        AND octet_length(payload_json) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_record PRIMARY KEY (tenant_id, thread_id, record_sequence),
    CONSTRAINT uk_agent_record_identity UNIQUE (tenant_id, thread_id, turn_id, user_id, record_id),
    CONSTRAINT uk_agent_record_product_reference UNIQUE (tenant_id, thread_id, turn_id, record_id),
    CONSTRAINT fk_agent_record_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_record_execution_page
    ON agent_record (tenant_id, thread_id, turn_id, user_id, record_sequence);

CREATE TABLE IF NOT EXISTS agent_event (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND turn_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    event_id VARCHAR(256) NOT NULL CHECK (length(event_id) BETWEEN 1 AND 256 AND event_id !~ '^[[:space:]]*$'),
    event_type VARCHAR(256) NOT NULL CHECK (length(event_type) BETWEEN 1 AND 256 AND event_type !~ '^[[:space:]]*$'),
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version > 0),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    last_record_sequence BIGINT NOT NULL CHECK (last_record_sequence >= 0),
    payload_json TEXT NOT NULL CONSTRAINT ck_agent_event_payload_json CHECK (
        jsonb_typeof(payload_json::jsonb) = 'object'
        AND octet_length(payload_json) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_event PRIMARY KEY (tenant_id, thread_id, event_sequence),
    CONSTRAINT uk_agent_event_identity UNIQUE (tenant_id, thread_id, event_id),
    CONSTRAINT fk_agent_event_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_operation_receipt (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    operation_key VARCHAR(256) NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 256 AND operation_key !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND turn_id !~ '^[[:space:]]*$'),
    operation_type VARCHAR(256) NOT NULL CHECK (length(operation_type) BETWEEN 1 AND 256 AND operation_type !~ '^[[:space:]]*$'),
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    first_event_sequence BIGINT NOT NULL CHECK (first_event_sequence >= 0),
    last_event_sequence BIGINT NOT NULL CHECK (last_event_sequence >= first_event_sequence),
    receipt_json TEXT NOT NULL CONSTRAINT ck_agent_operation_receipt_json CHECK (
        jsonb_typeof(receipt_json::jsonb) = 'object'
        AND octet_length(receipt_json) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_operation_receipt PRIMARY KEY (tenant_id, user_id, operation_key),
    CONSTRAINT fk_agent_operation_receipt_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_commit_receipt (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND turn_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    commit_key VARCHAR(256) NOT NULL CHECK (length(commit_key) BETWEEN 1 AND 256 AND commit_key !~ '^[[:space:]]*$'),
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    first_event_sequence BIGINT NOT NULL CHECK (first_event_sequence >= 0),
    last_event_sequence BIGINT NOT NULL CHECK (last_event_sequence >= first_event_sequence),
    receipt_json TEXT NOT NULL CONSTRAINT ck_agent_commit_receipt_json CHECK (
        jsonb_typeof(receipt_json::jsonb) = 'object'
        AND octet_length(receipt_json) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_commit_receipt PRIMARY KEY (tenant_id, thread_id, turn_id, user_id, commit_key),
    CONSTRAINT fk_agent_commit_receipt_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_lease (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND tenant_id !~ '^[[:space:]]*$'),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND thread_id !~ '^[[:space:]]*$'),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND user_id !~ '^[[:space:]]*$'),
    owner_id VARCHAR(256) NOT NULL CHECK (length(owner_id) BETWEEN 1 AND 256 AND owner_id !~ '^[[:space:]]*$'),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    expires_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT pk_agent_lease PRIMARY KEY (tenant_id, thread_id),
    CONSTRAINT fk_agent_lease_conversation FOREIGN KEY (tenant_id, thread_id, user_id)
        REFERENCES agent_conversation (tenant_id, thread_id, user_id)
);
