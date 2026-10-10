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
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    event_sequence BIGINT NOT NULL CHECK (event_sequence >= 0),
    record_sequence BIGINT NOT NULL CHECK (record_sequence >= 0),
    latest_execution_sequence BIGINT NOT NULL CHECK (latest_execution_sequence >= 0),
    created_at_ms BIGINT NOT NULL CHECK (typeof(created_at_ms) = 'integer'),
    updated_at_ms BIGINT NOT NULL CHECK (typeof(updated_at_ms) = 'integer'),
    CONSTRAINT pk_agent_conversation PRIMARY KEY (tenant_id, thread_id),
    CONSTRAINT uk_agent_conversation_owner UNIQUE (tenant_id, thread_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_execution (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND length(trim(turn_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(turn_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    admission_sequence BIGINT NOT NULL CHECK (admission_sequence > 0),
    state VARCHAR(128) NOT NULL CONSTRAINT ck_agent_execution_state CHECK (state IN ('QUEUED','RUNNING','WAITING_APPROVAL','WAITING_INPUT','SUSPENDED','COMPLETED','FAILED','CANCELLED')),
    mutation_version BIGINT NOT NULL CHECK (mutation_version >= 0),
    cursor_schema_version INTEGER NOT NULL CHECK (cursor_schema_version > 0),
    cursor_json TEXT NOT NULL CONSTRAINT ck_agent_execution_cursor_json CHECK (
        json_valid(cursor_json)
        AND json_type(cursor_json) = 'object'
        AND length(CAST(cursor_json AS BLOB)) <= 16777216
    ),
    last_record_sequence BIGINT NOT NULL CHECK (last_record_sequence >= 0),
    created_at_ms BIGINT NOT NULL CHECK (typeof(created_at_ms) = 'integer'),
    updated_at_ms BIGINT NOT NULL CHECK (typeof(updated_at_ms) = 'integer'),
    CONSTRAINT pk_agent_execution PRIMARY KEY (tenant_id, thread_id, turn_id, user_id),
    CONSTRAINT uk_agent_execution_admission UNIQUE (tenant_id, thread_id, admission_sequence),
    CONSTRAINT fk_agent_execution_conversation FOREIGN KEY (tenant_id, thread_id, user_id)
        REFERENCES agent_conversation (tenant_id, thread_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_record (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND length(trim(turn_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(turn_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    record_id VARCHAR(256) NOT NULL CHECK (length(record_id) BETWEEN 1 AND 256 AND length(trim(record_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(record_id,char(0))=0),
    record_type VARCHAR(256) NOT NULL CHECK (length(record_type) BETWEEN 1 AND 256 AND length(trim(record_type,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(record_type,char(0))=0),
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version > 0),
    record_sequence BIGINT NOT NULL CHECK (record_sequence > 0),
    payload_json TEXT NOT NULL CONSTRAINT ck_agent_record_payload_json CHECK (
        json_valid(payload_json)
        AND json_type(payload_json) = 'object'
        AND length(CAST(payload_json AS BLOB)) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL CHECK (typeof(occurred_at_ms) = 'integer'),
    CONSTRAINT pk_agent_record PRIMARY KEY (tenant_id, thread_id, record_sequence),
    CONSTRAINT uk_agent_record_identity UNIQUE (tenant_id, thread_id, turn_id, user_id, record_id),
    CONSTRAINT uk_agent_record_product_reference UNIQUE (tenant_id, thread_id, turn_id, record_id),
    CONSTRAINT fk_agent_record_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_record_execution_page
    ON agent_record (tenant_id, thread_id, turn_id, user_id, record_sequence);

CREATE TABLE IF NOT EXISTS agent_event (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND length(trim(turn_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(turn_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    event_id VARCHAR(256) NOT NULL CHECK (length(event_id) BETWEEN 1 AND 256 AND length(trim(event_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(event_id,char(0))=0),
    event_type VARCHAR(256) NOT NULL CHECK (length(event_type) BETWEEN 1 AND 256 AND length(trim(event_type,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(event_type,char(0))=0),
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version > 0),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    last_record_sequence BIGINT NOT NULL CHECK (last_record_sequence >= 0),
    payload_json TEXT NOT NULL CONSTRAINT ck_agent_event_payload_json CHECK (
        json_valid(payload_json)
        AND json_type(payload_json) = 'object'
        AND length(CAST(payload_json AS BLOB)) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL CHECK (typeof(occurred_at_ms) = 'integer'),
    CONSTRAINT pk_agent_event PRIMARY KEY (tenant_id, thread_id, event_sequence),
    CONSTRAINT uk_agent_event_identity UNIQUE (tenant_id, thread_id, event_id),
    CONSTRAINT fk_agent_event_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_operation_receipt (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    operation_key VARCHAR(256) NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 256 AND length(trim(operation_key,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(operation_key,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND length(trim(turn_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(turn_id,char(0))=0),
    operation_type VARCHAR(256) NOT NULL CHECK (length(operation_type) BETWEEN 1 AND 256 AND length(trim(operation_type,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(operation_type,char(0))=0),
    fingerprint CHAR(64) NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    first_event_sequence BIGINT NOT NULL CHECK (first_event_sequence >= 0),
    last_event_sequence BIGINT NOT NULL CHECK (last_event_sequence >= first_event_sequence),
    receipt_json TEXT NOT NULL CONSTRAINT ck_agent_operation_receipt_json CHECK (
        json_valid(receipt_json)
        AND json_type(receipt_json) = 'object'
        AND length(CAST(receipt_json AS BLOB)) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL CHECK (typeof(occurred_at_ms) = 'integer'),
    CONSTRAINT pk_agent_operation_receipt PRIMARY KEY (tenant_id, user_id, operation_key),
    CONSTRAINT fk_agent_operation_receipt_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_commit_receipt (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    turn_id VARCHAR(256) NOT NULL CHECK (length(turn_id) BETWEEN 1 AND 256 AND length(trim(turn_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(turn_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    commit_key VARCHAR(256) NOT NULL CHECK (length(commit_key) BETWEEN 1 AND 256 AND length(trim(commit_key,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(commit_key,char(0))=0),
    fingerprint CHAR(64) NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_revision BIGINT NOT NULL CHECK (thread_revision > 0),
    mutation_version BIGINT NOT NULL CHECK (mutation_version > 0),
    first_event_sequence BIGINT NOT NULL CHECK (first_event_sequence >= 0),
    last_event_sequence BIGINT NOT NULL CHECK (last_event_sequence >= first_event_sequence),
    receipt_json TEXT NOT NULL CONSTRAINT ck_agent_commit_receipt_json CHECK (
        json_valid(receipt_json)
        AND json_type(receipt_json) = 'object'
        AND length(CAST(receipt_json AS BLOB)) <= 16777216
    ),
    occurred_at_ms BIGINT NOT NULL CHECK (typeof(occurred_at_ms) = 'integer'),
    CONSTRAINT pk_agent_commit_receipt PRIMARY KEY (tenant_id, thread_id, turn_id, user_id, commit_key),
    CONSTRAINT fk_agent_commit_receipt_execution FOREIGN KEY (tenant_id, thread_id, turn_id, user_id)
        REFERENCES agent_execution (tenant_id, thread_id, turn_id, user_id)
);

CREATE TABLE IF NOT EXISTS agent_lease (
    tenant_id VARCHAR(256) NOT NULL CHECK (length(tenant_id) BETWEEN 1 AND 256 AND length(trim(tenant_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(tenant_id,char(0))=0),
    thread_id VARCHAR(256) NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 256 AND length(trim(thread_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(thread_id,char(0))=0),
    user_id VARCHAR(256) NOT NULL CHECK (length(user_id) BETWEEN 1 AND 256 AND length(trim(user_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(user_id,char(0))=0),
    owner_id VARCHAR(256) NOT NULL CHECK (length(owner_id) BETWEEN 1 AND 256 AND length(trim(owner_id,char(9)||char(10)||char(11)||char(12)||char(13)||char(28)||char(29)||char(30)||char(31)||' '||char(5760)||char(8192)||char(8193)||char(8194)||char(8195)||char(8196)||char(8198)||char(8200)||char(8201)||char(8202)||char(8232)||char(8233)||char(8287)||char(12288)))>0 AND instr(owner_id,char(0))=0),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    expires_at_ms BIGINT NOT NULL CHECK (typeof(expires_at_ms) = 'integer'),
    updated_at_ms BIGINT NOT NULL CHECK (typeof(updated_at_ms) = 'integer'),
    CONSTRAINT pk_agent_lease PRIMARY KEY (tenant_id, thread_id),
    CONSTRAINT fk_agent_lease_conversation FOREIGN KEY (tenant_id, thread_id, user_id)
        REFERENCES agent_conversation (tenant_id, thread_id, user_id)
);
