-- @author kongweiguang
-- 全新 Ja V1 基线：唯一 SQLite 权威存储、最终 selector、无事件 journal、无旧 profile/config revision 列。
-- selector 允许为 null，用于表达通用工作区，不引入旧 sentinel 值。

CREATE TABLE workspaces (
    workspace_id TEXT PRIMARY KEY NOT NULL,
    root_path TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    trust TEXT NOT NULL CHECK (trust IN ('TRUSTED', 'UNTRUSTED')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE threads (
    thread_id TEXT PRIMARY KEY NOT NULL,
    workspace_id TEXT NOT NULL,
    title TEXT NOT NULL,
    profile_id TEXT,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    archived_at TEXT,
    deleted_at TEXT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(workspace_id) ON DELETE RESTRICT
);

CREATE INDEX idx_threads_workspace_updated ON threads(workspace_id, updated_at DESC, thread_id DESC);
CREATE INDEX idx_threads_workspace_profile ON threads(workspace_id, profile_id, updated_at DESC, thread_id DESC);

CREATE TABLE turns (
    turn_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    profile_id TEXT,
    config_generation TEXT,
    mutation_version INTEGER NOT NULL DEFAULT 0 CHECK (mutation_version >= 0),
    requested_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    completed_at TEXT,
    terminal_summary TEXT,
    error_code TEXT,
    error_message TEXT,
    cancel_requested_at TEXT,
    cancel_reason TEXT,
    cancel_expected_thread_revision INTEGER,
    cancel_thread_revision INTEGER,
    cancel_turn_mutation_version INTEGER,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    CHECK ((state IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL)),
    CHECK ((state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (terminal_summary IS NULL)),
    CHECK (cancel_expected_thread_revision IS NULL OR cancel_expected_thread_revision >= 0),
    CHECK (cancel_thread_revision IS NULL OR cancel_thread_revision >= 0),
    CHECK (cancel_turn_mutation_version IS NULL OR cancel_turn_mutation_version >= 0),
    CHECK ((cancel_requested_at IS NULL)
        = (cancel_reason IS NULL
            AND cancel_expected_thread_revision IS NULL
            AND cancel_thread_revision IS NULL
            AND cancel_turn_mutation_version IS NULL))
);

CREATE INDEX idx_turns_thread_requested ON turns(thread_id, requested_at, turn_id);
CREATE UNIQUE INDEX uq_turns_one_active ON turns(thread_id)
    WHERE state IN ('RUNNING', 'WAITING_APPROVAL');
CREATE INDEX idx_turns_generation ON turns(config_generation, profile_id, requested_at, turn_id);

CREATE TABLE messages (
    message_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 1),
    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    blocks_json TEXT NOT NULL CHECK (json_valid(blocks_json) AND json_type(blocks_json) = 'array'),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (thread_id, ordinal)
);

CREATE INDEX idx_messages_thread_ordinal ON messages(thread_id, ordinal);
CREATE TRIGGER messages_immutable_update BEFORE UPDATE ON messages
BEGIN SELECT RAISE(ABORT, 'messages are immutable'); END;
CREATE TRIGGER messages_immutable_delete BEFORE DELETE ON messages
BEGIN SELECT RAISE(ABORT, 'messages are immutable'); END;

CREATE TABLE tools (
    call_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    tool_name TEXT NOT NULL,
    side_effect TEXT NOT NULL CHECK (side_effect IN ('READ_ONLY', 'EXTERNAL')),
    arguments_json TEXT NOT NULL CHECK (json_valid(arguments_json) AND json_type(arguments_json) = 'object'),
    state TEXT NOT NULL CHECK (state IN ('PREPARED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    result_content TEXT,
    result_error INTEGER CHECK (result_error IS NULL OR result_error IN (0, 1)),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id, ordinal),
    UNIQUE (turn_id, call_id),
    CHECK ((state IN ('PREPARED', 'RUNNING')) = (result_content IS NULL)),
    CHECK ((result_content IS NULL) = (result_error IS NULL))
);

CREATE INDEX idx_tools_recovery ON tools(state, thread_id, turn_id);

CREATE TABLE approvals (
    approval_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    decision TEXT CHECK (decision IS NULL OR decision IN ('APPROVE', 'DENY')),
    requested_at TEXT NOT NULL,
    resolved_at TEXT,
    expires_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (call_id) REFERENCES tools(call_id) ON DELETE RESTRICT,
    UNIQUE (turn_id, call_id),
    CHECK ((decision IS NULL) = (resolved_at IS NULL))
);

CREATE INDEX idx_approvals_pending ON approvals(thread_id, expires_at) WHERE decision IS NULL;

CREATE TABLE pending_inputs (
    input_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('STEERING', 'FOLLOW_UP')),
    text TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'CONSUMED', 'CANCELLED')),
    created_at TEXT NOT NULL,
    consumed_at TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((state = 'PENDING') = (consumed_at IS NULL))
);

CREATE INDEX idx_pending_inputs_fifo ON pending_inputs(turn_id, kind, state, created_at, input_id);

CREATE TABLE usage (
    usage_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    model_round INTEGER NOT NULL CHECK (model_round BETWEEN 1 AND 128),
    input_tokens INTEGER NOT NULL CHECK (input_tokens >= 0),
    output_tokens INTEGER NOT NULL CHECK (output_tokens >= 0),
    total_tokens INTEGER NOT NULL CHECK (total_tokens >= input_tokens + output_tokens),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id, model_round)
);

CREATE TRIGGER usage_immutable_update BEFORE UPDATE ON usage
BEGIN SELECT RAISE(ABORT, 'usage is immutable'); END;
CREATE TRIGGER usage_immutable_delete BEFORE DELETE ON usage
BEGIN SELECT RAISE(ABORT, 'usage is immutable'); END;

CREATE TABLE context_checkpoints (
    checkpoint_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    through_ordinal INTEGER NOT NULL CHECK (through_ordinal >= 0),
    retained_from_ordinal INTEGER NOT NULL CHECK (retained_from_ordinal >= through_ordinal),
    retained_split_json TEXT CHECK (retained_split_json IS NULL OR (
        json_valid(retained_split_json) AND json_type(retained_split_json) = 'object'
    )),
    summary_json TEXT NOT NULL CHECK (json_valid(summary_json) AND json_type(summary_json) = 'object'),
    input_tokens INTEGER NOT NULL CHECK (input_tokens >= 0),
    envelope_fingerprint TEXT NOT NULL CHECK (
        length(envelope_fingerprint) = 64 AND envelope_fingerprint NOT GLOB '*[^0-9a-f]*'
    ),
    strategy_version TEXT NOT NULL CHECK (strategy_version = 'ja-context-v3'),
    usage_json TEXT NOT NULL CHECK (json_valid(usage_json) AND json_type(usage_json) = 'object'),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    UNIQUE (thread_id, source_revision),
    CHECK (retained_split_json IS NULL OR retained_from_ordinal = through_ordinal)
);

CREATE INDEX idx_context_checkpoints_thread_source
    ON context_checkpoints(thread_id, source_revision DESC);
CREATE TRIGGER context_checkpoints_immutable_update BEFORE UPDATE ON context_checkpoints
BEGIN SELECT RAISE(ABORT, 'context checkpoints are immutable'); END;
CREATE TRIGGER context_checkpoints_immutable_delete BEFORE DELETE ON context_checkpoints
BEGIN SELECT RAISE(ABORT, 'context checkpoints are immutable'); END;

-- Thread 只保存已发现目录，不复制 AGENTS 正文或摘要；首次发布直接建立最终结构。
CREATE TABLE thread_instruction_scopes (
    thread_id TEXT NOT NULL,
    relative_directory TEXT NOT NULL,
    discovered_at TEXT NOT NULL,
    PRIMARY KEY (thread_id, relative_directory),
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE
);

CREATE INDEX idx_instruction_scopes_thread_directory
    ON thread_instruction_scopes(thread_id, relative_directory);
