-- @author kongweiguang
-- Ja 0.1.0 的唯一首版 schema：直接创建当前领域模型，不包含历史迁移、回填或兼容入口。
-- Flyway schema history/checksum 是数据库版本的唯一权威来源。
-- TABLES

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
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    archived_at TEXT,
    deleted_at TEXT,
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    access_mode TEXT NOT NULL CHECK (access_mode IN ('APPROVAL_REQUIRED','FULL_ACCESS')),
    reasoning_level TEXT CHECK (
        reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
    ),
    pinned_at TEXT,
    last_seen_turn_sequence INTEGER CHECK (last_seen_turn_sequence IS NULL OR last_seen_turn_sequence > 0),
    collaboration_mode TEXT NOT NULL DEFAULT 'DEFAULT' CHECK (collaboration_mode IN ('DEFAULT','PLAN')),
    title_source TEXT NOT NULL CHECK (title_source IN ('PLACEHOLDER','AUTO','MANUAL')),
    FOREIGN KEY (workspace_id) REFERENCES workspaces(workspace_id) ON DELETE RESTRICT
);

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
    strategy_version TEXT NOT NULL CHECK (strategy_version = 'ja-context-v1'),
    usage_json TEXT NOT NULL CHECK (json_valid(usage_json) AND json_type(usage_json) = 'object'),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    UNIQUE (thread_id, source_revision),
    CHECK (retained_split_json IS NULL OR retained_from_ordinal = through_ordinal)
);

CREATE TABLE thread_instruction_scopes (
    thread_id TEXT NOT NULL,
    relative_directory TEXT NOT NULL,
    discovered_at TEXT NOT NULL,
    PRIMARY KEY (thread_id, relative_directory),
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE
);

CREATE TABLE attachment_blobs (
    sha256 TEXT PRIMARY KEY NOT NULL CHECK (
        length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 104857600),
    media_kind TEXT NOT NULL CHECK (media_kind IN ('TEXT','IMAGE','PDF','BINARY')),
    media_type TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE attachments (
    attachment_id TEXT PRIMARY KEY NOT NULL,
    workspace_id TEXT NOT NULL,
    blob_sha256 TEXT,
    content_sha256 TEXT NOT NULL CHECK (
        length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    display_name TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 104857600),
    media_kind TEXT NOT NULL CHECK (media_kind IN ('TEXT','IMAGE','PDF','BINARY')),
    media_type TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','BOUND','DISCARDED','EXPIRED')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    bound_at TEXT,
    discarded_at TEXT,
    expired_at TEXT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (blob_sha256) REFERENCES attachment_blobs(sha256) ON DELETE SET NULL,
    CHECK ((status='BOUND')=(bound_at IS NOT NULL)),
    CHECK ((status='DISCARDED')=(discarded_at IS NOT NULL)),
    CHECK ((status='EXPIRED')=(expired_at IS NOT NULL)),
    CHECK ((status IN ('DRAFT','BOUND'))=(blob_sha256 IS NOT NULL))
);

CREATE TABLE thread_title_generations (
    generation_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL UNIQUE,
    turn_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    config_generation TEXT NOT NULL,
    result TEXT CHECK (result IS NULL OR result IN ('SUCCEEDED','FAILED')),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens>=0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens>=0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens>=0),
    failure_code TEXT,
    claimed_at TEXT NOT NULL,
    completed_at TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((result IS NULL)=(completed_at IS NULL)),
    CHECK (result IS NULL OR result='FAILED' OR (
        input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL
        AND total_tokens>=input_tokens+output_tokens
    )),
    CHECK (result IS NULL OR result='SUCCEEDED' OR failure_code IS NOT NULL),
    CHECK (result IS NULL OR result='FAILED' OR failure_code IS NULL),
    CHECK ((input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)
        OR (input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL
            AND total_tokens>=input_tokens+output_tokens))
);

CREATE TABLE timeline_messages (
    item_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    message_kind TEXT NOT NULL CHECK (message_kind IN (
        'USER_INPUT','THREAD_MESSAGE','ASSISTANT_PROGRESS','REASONING_SUMMARY','FINAL_ANSWER'
    )),
    public_text TEXT NOT NULL,
    model_round INTEGER CHECK (model_round BETWEEN 1 AND 128),
    created_at TEXT NOT NULL,
    source_thread_id TEXT,
    source_title TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((message_kind IN ('ASSISTANT_PROGRESS','REASONING_SUMMARY')) = (model_round IS NOT NULL)),
    CHECK ((message_kind='THREAD_MESSAGE') = (source_thread_id IS NOT NULL AND source_title IS NOT NULL)),
    CHECK (message_kind!='THREAD_MESSAGE' OR length(source_title) BETWEEN 1 AND 512)
);

CREATE TABLE approvals (
    approval_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    decision TEXT CHECK (decision IS NULL OR decision IN ('APPROVE','DENY')),
    requested_at TEXT NOT NULL,
    resolved_at TEXT,
    expires_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (call_id) REFERENCES tools(call_id) ON DELETE RESTRICT,
    UNIQUE (turn_id,call_id),
    CHECK ((decision IS NULL) = (resolved_at IS NULL))
);

CREATE TABLE tool_artifacts (
    artifact_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    content TEXT NOT NULL,
    character_length INTEGER NOT NULL CHECK (character_length >= 0),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (call_id) REFERENCES tools(call_id) ON DELETE RESTRICT,
    UNIQUE(thread_id,turn_id,call_id,artifact_id)
);

CREATE TABLE turn_change_sets (
    turn_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    change_set_json TEXT NOT NULL CHECK (json_valid(change_set_json) AND json_type(change_set_json)='object'),
    artifact_id TEXT,
    committed_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(workspace_id) ON DELETE RESTRICT
);

CREATE TABLE change_set_artifacts (
    artifact_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    content TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    byte_length INTEGER NOT NULL CHECK (byte_length BETWEEN 0 AND 2097152),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE(thread_id,turn_id,artifact_id)
);

CREATE TABLE "tools" (
    call_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    tool_name TEXT NOT NULL,
    side_effect TEXT NOT NULL CHECK (side_effect IN ('READ_ONLY','EXTERNAL')),
    presentation_json TEXT NOT NULL CHECK (json_valid(presentation_json) AND json_type(presentation_json)='object'),
    artifact_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('PREPARED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id,ordinal),UNIQUE (turn_id,call_id)
);

CREATE TABLE task_context_seeds (
    context_seed_id TEXT PRIMARY KEY NOT NULL,
    parent_thread_id TEXT NOT NULL,
    parent_turn_id TEXT,
    parent_revision INTEGER NOT NULL CHECK (parent_revision >= 0),
    inheritance_mode TEXT NOT NULL CHECK (inheritance_mode IN ('EFFECTIVE_CONTEXT','BRIEF_ONLY')),
    task_brief_json TEXT CHECK (
        json_valid(task_brief_json) AND json_type(task_brief_json)='array'
        OR task_brief_json IS NULL
    ),
    effective_context_json TEXT CHECK (
        effective_context_json IS NULL OR
        (json_valid(effective_context_json) AND json_type(effective_context_json)='object')
    ),
    references_json TEXT NOT NULL CHECK (
        json_valid(references_json) AND json_type(references_json)='array'
    ),
    permission_ceiling_json TEXT NOT NULL CHECK (
        json_valid(permission_ceiling_json) AND json_type(permission_ceiling_json)='object'
    ),
    fingerprint TEXT NOT NULL CHECK (
        length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'
    ),
    created_at TEXT NOT NULL,
    FOREIGN KEY (parent_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (parent_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((inheritance_mode='EFFECTIVE_CONTEXT') = (effective_context_json IS NOT NULL))
);

CREATE TABLE thread_lineage (
    child_thread_id TEXT PRIMARY KEY NOT NULL,
    parent_thread_id TEXT NOT NULL,
    root_thread_id TEXT NOT NULL,
    origin_turn_id TEXT,
    task_name TEXT NOT NULL CHECK (length(task_name) BETWEEN 1 AND 96),
    depth INTEGER NOT NULL CHECK (depth BETWEEN 1 AND 4),
    task_kind TEXT NOT NULL CHECK (task_kind IN ('SIDE_TASK','SUBAGENT')),
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('INDEPENDENT','ATTACHED')),
    context_seed_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    FOREIGN KEY (child_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (parent_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (root_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (origin_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (context_seed_id) REFERENCES task_context_seeds(context_seed_id) ON DELETE RESTRICT,
    CHECK (child_thread_id<>parent_thread_id AND child_thread_id<>root_thread_id),
    CHECK ((task_kind='SIDE_TASK' AND lifecycle='INDEPENDENT') OR
           (task_kind='SUBAGENT' AND lifecycle='ATTACHED'))
);

-- 用户侧边会话以 Thread ID 区分，默认同名合法；模型派发的子智能体仍维持兄弟名称唯一。
CREATE UNIQUE INDEX ux_thread_lineage_agent_name
    ON thread_lineage(parent_thread_id,task_name) WHERE task_kind='SUBAGENT';

-- 只有新临时侧聊登记此标记；旧持久侧任务不因启动恢复而被推断成可删除数据。
CREATE TABLE temporary_side_chats (
    thread_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('OPEN','CLOSING')),
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE
);

CREATE TABLE task_mailbox (
    mailbox_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT UNIQUE NOT NULL,
    root_thread_id TEXT NOT NULL,
    sender_thread_id TEXT NOT NULL,
    sender_title TEXT NOT NULL,
    target_thread_id TEXT NOT NULL,
    causal_turn_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('MESSAGE','FOLLOW_UP','FINAL_ANSWER')),
    content_json TEXT NOT NULL CHECK (
        json_valid(content_json) AND json_type(content_json)='array'
    ),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    state TEXT NOT NULL CHECK (state IN ('PENDING','BOUND','CONSUMED','CANCELLED')),
    bound_turn_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    consumed_at TEXT,
    FOREIGN KEY (root_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (target_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (bound_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (sender_thread_id, idempotency_key),
    CHECK (kind='FOLLOW_UP' OR sender_thread_id<>target_thread_id),
    CHECK ((state='BOUND') = (bound_turn_id IS NOT NULL AND consumed_at IS NULL)),
    CHECK ((state='CONSUMED') = (consumed_at IS NOT NULL)),
    CHECK (state!='PENDING' OR (bound_turn_id IS NULL AND consumed_at IS NULL)),
    CHECK (state!='CANCELLED' OR consumed_at IS NULL)
);

CREATE TABLE task_activities (
    activity_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    activity_id TEXT UNIQUE NOT NULL,
    root_thread_id TEXT NOT NULL,
    task_thread_id TEXT NOT NULL,
    actor_thread_id TEXT NOT NULL,
    causal_turn_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN (
        'CREATED','DISPATCHED','MESSAGE_SENT','FOLLOW_UP_QUEUED','PROGRESS','WAITING_APPROVAL',
        'RESUMED','COMPLETED','FAILED','CANCELLED','SUSPENDED'
    )),
    summary_json TEXT NOT NULL CHECK (
        json_valid(summary_json) AND json_type(summary_json)='object'
    ),
    created_at TEXT NOT NULL,
    FOREIGN KEY (root_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (task_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (actor_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (causal_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT
);

CREATE TABLE task_projections (
    task_thread_id TEXT PRIMARY KEY NOT NULL,
    root_thread_id TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    state TEXT NOT NULL CHECK (state IN (
        'IDLE','QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED','COMPLETED','FAILED','CANCELLED'
    )),
    latest_activity_sequence INTEGER NOT NULL CHECK (latest_activity_sequence > 0),
    last_seen_activity_sequence INTEGER CHECK (
        last_seen_activity_sequence IS NULL OR last_seen_activity_sequence > 0
    ),
    unread_count INTEGER NOT NULL DEFAULT 0 CHECK (unread_count >= 0),
    descendant_count INTEGER NOT NULL DEFAULT 0 CHECK (descendant_count BETWEEN 0 AND 64),
    running_descendant_count INTEGER NOT NULL DEFAULT 0 CHECK (running_descendant_count BETWEEN 0 AND 64),
    needs_attention_count INTEGER NOT NULL DEFAULT 0 CHECK (needs_attention_count BETWEEN 0 AND 64),
    latest_safe_summary TEXT,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (task_thread_id) REFERENCES thread_lineage(child_thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (root_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (latest_activity_sequence) REFERENCES task_activities(activity_sequence) ON DELETE RESTRICT,
    CHECK (last_seen_activity_sequence IS NULL OR last_seen_activity_sequence<=latest_activity_sequence),
    CHECK ((state IN ('COMPLETED','FAILED','CANCELLED')) = (completed_at IS NOT NULL))
);

CREATE TABLE workspace_write_claims (
    claim_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    claim_id TEXT UNIQUE NOT NULL,
    workspace_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    process_generation INTEGER NOT NULL CHECK (process_generation >= 1),
    fencing_token INTEGER NOT NULL CHECK (fencing_token >= 1),
    state TEXT NOT NULL CHECK (state IN ('WAITING','HELD','RELEASED','ABANDONED')),
    requested_at TEXT NOT NULL,
    acquired_at TEXT,
    heartbeat_at TEXT,
    released_at TEXT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (workspace_id, fencing_token),
    CHECK ((state='WAITING') = (acquired_at IS NULL AND heartbeat_at IS NULL AND released_at IS NULL)),
    CHECK ((state='HELD') = (acquired_at IS NOT NULL AND heartbeat_at IS NOT NULL AND released_at IS NULL)),
    CHECK ((state IN ('RELEASED','ABANDONED')) = (released_at IS NOT NULL))
);

CREATE TABLE message_attachments (
    message_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at TEXT NOT NULL,
    PRIMARY KEY (message_id, ordinal),
    FOREIGN KEY (message_id) REFERENCES messages(message_id) ON DELETE RESTRICT,
    FOREIGN KEY (attachment_id) REFERENCES attachments(attachment_id) ON DELETE RESTRICT
);

CREATE TABLE pending_inputs (
    input_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    input_id TEXT UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('STEERING','FOLLOW_UP')),
    content_json TEXT NOT NULL CHECK (json_valid(content_json) AND json_type(content_json)='array'),
    state TEXT NOT NULL CHECK (state IN ('PENDING','CONSUMED','CANCELLED')),
    validation_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (validation_status IN ('PENDING','NEEDS_ATTENTION')),
    issue_error_code TEXT CHECK (issue_error_code IS NULL OR issue_error_code IN (
        'WORKSPACE_REFERENCE_INVALID','SKILL_UNAVAILABLE','SKILL_LOAD_FAILED','CONTENT_TOO_LARGE',
        'ATTACHMENT_UNAVAILABLE'
    )),
    issue_message TEXT,
    issue_retryable INTEGER CHECK (issue_retryable IS NULL OR issue_retryable IN (0,1)),
    input_revision INTEGER NOT NULL DEFAULT 1 CHECK (input_revision >= 1),
    priority_sequence INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    resolved_at TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((state='PENDING') = (resolved_at IS NULL)),
    CHECK ((kind='STEERING') = (priority_sequence IS NOT NULL)),
    CHECK (priority_sequence IS NULL OR priority_sequence >= 1),
    CHECK ((validation_status='PENDING'
            AND issue_error_code IS NULL AND issue_message IS NULL AND issue_retryable IS NULL)
        OR (validation_status='NEEDS_ATTENTION'
            AND issue_error_code IS NOT NULL AND issue_message IS NOT NULL AND issue_retryable IS NOT NULL)),
    CHECK (state='PENDING' OR validation_status='PENDING'),
    CHECK (state!='PENDING' OR (length(content_json) BETWEEN 1 AND 4194304
        AND length(CAST(content_json AS BLOB)) BETWEEN 1 AND 524288))
);

CREATE TABLE pending_input_attachments (
    input_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at TEXT NOT NULL,
    PRIMARY KEY (input_id, ordinal),
    FOREIGN KEY (input_id) REFERENCES pending_inputs(input_id) ON DELETE RESTRICT,
    FOREIGN KEY (attachment_id) REFERENCES attachments(attachment_id) ON DELETE RESTRICT
);

CREATE TABLE turns (
    turn_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    turn_id TEXT UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED','COMPLETED','FAILED','CANCELLED'
    )),
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
    input_queue_revision INTEGER NOT NULL DEFAULT 0 CHECK (input_queue_revision >= 0),
    accepting_inputs INTEGER NOT NULL DEFAULT 1 CHECK (accepting_inputs IN (0,1)),
    parent_turn_id TEXT,
    root_turn_id TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (parent_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (root_turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((state IN ('COMPLETED','FAILED','CANCELLED')) = (completed_at IS NOT NULL)),
    CHECK ((state NOT IN ('COMPLETED','FAILED','CANCELLED')) = (terminal_summary IS NULL)),
    CHECK (cancel_expected_thread_revision IS NULL OR cancel_expected_thread_revision >= 0),
    CHECK (cancel_thread_revision IS NULL OR cancel_thread_revision >= 0),
    CHECK (cancel_turn_mutation_version IS NULL OR cancel_turn_mutation_version >= 0),
    CHECK ((cancel_requested_at IS NULL)
        = (cancel_reason IS NULL AND cancel_expected_thread_revision IS NULL
            AND cancel_thread_revision IS NULL AND cancel_turn_mutation_version IS NULL))
);

CREATE TABLE usage (
    usage_id TEXT PRIMARY KEY NOT NULL,
    request_id TEXT UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    model_round INTEGER NOT NULL CHECK (model_round BETWEEN 1 AND 128),
    request_ordinal INTEGER NOT NULL CHECK (request_ordinal BETWEEN 1 AND 1024),
    purpose TEXT NOT NULL CHECK (purpose IN ('ASSISTANT','SUMMARY')),
    certainty TEXT NOT NULL CHECK (certainty IN ('KNOWN','UNKNOWN')),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens >= 0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens >= 0),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id,request_ordinal),
    CHECK ((certainty='UNKNOWN' AND input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)
        OR (certainty='KNOWN' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL
            AND total_tokens IS NOT NULL AND total_tokens >= input_tokens + output_tokens))
);

CREATE TABLE tool_bindings (
    turn_id TEXT NOT NULL,
    batch_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    route_kind TEXT NOT NULL CHECK (route_kind IN ('BUILTIN','MCP')),
    local_name TEXT NOT NULL,
    server_id TEXT NOT NULL,
    remote_name TEXT NOT NULL,
    schema_hash TEXT NOT NULL CHECK (length(schema_hash)=64 AND schema_hash NOT GLOB '*[^0-9a-f]*'),
    route_hash TEXT NOT NULL CHECK (length(route_hash)=64 AND route_hash NOT GLOB '*[^0-9a-f]*'),
    catalog_revision TEXT NOT NULL CHECK (
        length(catalog_revision)=64 AND catalog_revision NOT GLOB '*[^0-9a-f]*'),
    access_mode TEXT NOT NULL CHECK (access_mode IN ('APPROVAL_REQUIRED','FULL_ACCESS')),
    created_at TEXT NOT NULL,
    PRIMARY KEY (turn_id,call_id),
    FOREIGN KEY (turn_id,call_id) REFERENCES tools(turn_id,call_id) ON DELETE RESTRICT
);

CREATE TABLE task_process_generation (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    last_generation INTEGER NOT NULL CHECK (last_generation >= 0 AND last_generation < 9223372036854775807)
);

CREATE TABLE turn_execution (
    turn_id TEXT PRIMARY KEY NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    state_json TEXT NOT NULL CHECK (
        json_valid(state_json) AND json_type(state_json)='object'
        AND json_extract(state_json,'$.schemaVersion')=1
        AND json_extract(state_json,'$.common.origin') IN (
            'USER','CHILD_TASK','GOAL_CONTINUATION','PLAN_EXECUTION'
        )
        AND (json_extract(state_json,'$.kind')<>'PROVIDER_PENDING'
             OR json_extract(state_json,'$.profile.collaborationMode') IN ('DEFAULT','PLAN'))
    ),
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT
);

CREATE TABLE turn_internal_context (
    turn_id TEXT PRIMARY KEY NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('GOAL_CONTINUATION','PLAN_EXECUTION')),
    context_json TEXT NOT NULL CHECK (
        json_valid(context_json) AND json_type(context_json)='object'
        AND json_extract(context_json,'$.kind')=origin
    ),
    created_at TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT
);

CREATE TABLE goals (
    goal_id TEXT PRIMARY KEY NOT NULL,
    owner_thread_id TEXT NOT NULL,
    owner_kind TEXT NOT NULL CHECK (owner_kind IN ('ROOT_THREAD','INDEPENDENT_TASK')),
    objective TEXT NOT NULL CHECK (length(objective) BETWEEN 1 AND 32768),
    goal_definition_revision INTEGER NOT NULL CHECK (goal_definition_revision>=1),
    create_idempotency_key TEXT NOT NULL CHECK (length(create_idempotency_key) BETWEEN 8 AND 128),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','PAUSED','ACHIEVED','STOPPED')),
    phase TEXT NOT NULL CHECK (phase IN ('WORKING','WAITING_APPROVAL','WAITING_INPUT','VERIFYING','NEEDS_ATTENTION','PAUSED','ACHIEVED','STOPPED')),
    revision INTEGER NOT NULL CHECK (revision>=0),
    active_run_id TEXT NOT NULL,
    progress_turns_without_change INTEGER NOT NULL DEFAULT 0 CHECK (progress_turns_without_change BETWEEN 0 AND 3),
    repeated_failure_count INTEGER NOT NULL DEFAULT 0 CHECK (repeated_failure_count BETWEEN 0 AND 3),
    last_failure_signature TEXT,
    recovery_required INTEGER NOT NULL DEFAULT 0 CHECK (recovery_required IN (0,1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (owner_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (active_run_id) REFERENCES execution_runs(run_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((status='PAUSED')=(phase IN ('PAUSED','NEEDS_ATTENTION'))),
    CHECK ((status='ACHIEVED')=(phase='ACHIEVED')),
    CHECK ((status='STOPPED')=(phase='STOPPED')),
    UNIQUE (owner_thread_id,create_idempotency_key)
);

CREATE TABLE goal_definition_revisions (
    goal_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number>=1),
    objective TEXT NOT NULL CHECK (length(objective) BETWEEN 1 AND 32768),
    created_at TEXT NOT NULL,
    PRIMARY KEY (goal_id,revision_number),
    FOREIGN KEY (goal_id) REFERENCES goals(goal_id) ON DELETE RESTRICT
);

CREATE TABLE goal_acceptance_criteria (
    goal_id TEXT NOT NULL,
    goal_definition_revision INTEGER NOT NULL,
    criterion_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>=0),
    description TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 2000),
    required INTEGER NOT NULL CHECK (required IN (0,1)),
    PRIMARY KEY (goal_id,goal_definition_revision,criterion_id),
    UNIQUE (goal_id,goal_definition_revision,ordinal),
    FOREIGN KEY (goal_id,goal_definition_revision) REFERENCES goal_definition_revisions(goal_id,revision_number) ON DELETE RESTRICT
);

CREATE TABLE plans (
    plan_id TEXT PRIMARY KEY NOT NULL,
    owner_thread_id TEXT NOT NULL,
    objective TEXT NOT NULL CHECK (length(objective) BETWEEN 1 AND 32768),
    create_idempotency_key TEXT NOT NULL CHECK (length(create_idempotency_key) BETWEEN 8 AND 128),
    status TEXT NOT NULL CHECK (status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','PAUSED','COMPLETED','STOPPED')),
    revision INTEGER NOT NULL CHECK (revision>=0),
    active_plan_revision_id TEXT,
    active_run_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (owner_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (active_plan_revision_id) REFERENCES plan_revisions(plan_revision_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (active_run_id) REFERENCES execution_runs(run_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (status NOT IN ('APPROVED','EXECUTING','VERIFYING','PAUSED','COMPLETED') OR active_plan_revision_id IS NOT NULL),
    CHECK (status NOT IN ('EXECUTING','VERIFYING','PAUSED','COMPLETED') OR active_run_id IS NOT NULL),
    CHECK (status NOT IN ('DRAFT','AWAITING_APPROVAL','APPROVED') OR active_run_id IS NULL),
    UNIQUE (owner_thread_id,create_idempotency_key)
);

-- 问答与 Tool 游标共享 SQLite 权威事务，关闭界面不得删除待回答请求。
CREATE TABLE interaction_requests (
    request_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    tool_call_id TEXT NOT NULL,
    plan_revision_id TEXT,
    run_id TEXT,
    goal_id TEXT,
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 256),
    questions_json TEXT NOT NULL CHECK (json_valid(questions_json) AND json_type(questions_json)='array'
        AND json_array_length(questions_json) BETWEEN 1 AND 3),
    answers_json TEXT NOT NULL CHECK (json_valid(answers_json) AND json_type(answers_json)='array'
        AND json_array_length(answers_json)<=3),
    status TEXT NOT NULL CHECK (status IN ('PENDING','ANSWERED','CANCELLED','SUPERSEDED')),
    revision INTEGER NOT NULL CHECK (revision>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_revision_id) REFERENCES plan_revisions(plan_revision_id) ON DELETE RESTRICT,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    FOREIGN KEY (goal_id) REFERENCES goals(goal_id) ON DELETE RESTRICT,
    UNIQUE (thread_id,idempotency_key),
    UNIQUE (turn_id,tool_call_id)
);

CREATE TABLE interaction_drafts (
    request_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    answers_json TEXT NOT NULL CHECK (json_valid(answers_json) AND json_type(answers_json)='array'
        AND json_array_length(answers_json)<=3),
    page INTEGER NOT NULL CHECK (page BETWEEN 0 AND 2),
    collapsed INTEGER NOT NULL CHECK (collapsed IN (0,1)),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 256),
    revision INTEGER NOT NULL CHECK (revision>=0),
    updated_at TEXT NOT NULL,
    FOREIGN KEY (request_id) REFERENCES interaction_requests(request_id) ON DELETE RESTRICT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT
);

CREATE TABLE interaction_events (
    event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    thread_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    request_revision INTEGER NOT NULL CHECK (request_revision>=0),
    kind TEXT NOT NULL CHECK (kind IN ('CREATED','DRAFT_CHANGED','ANSWERED','CANCELLED','SUPERSEDED')),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (request_id) REFERENCES interaction_requests(request_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_interaction_thread_pending ON interaction_requests(thread_id) WHERE status='PENDING';
CREATE INDEX idx_interaction_events_thread ON interaction_events(thread_id,event_sequence);

-- 问题可关联独立 Plan 或 Goal，但关联必须属于同一真实 Turn owner 与冻结 Run。
CREATE TRIGGER interaction_request_owner_insert BEFORE INSERT ON interaction_requests
WHEN NOT EXISTS(SELECT 1 FROM turns t WHERE t.turn_id=NEW.turn_id AND t.thread_id=NEW.thread_id)
 OR (NEW.run_id IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM execution_runs r WHERE r.run_id=NEW.run_id
      AND r.goal_id IS NEW.goal_id AND r.plan_revision_id IS NEW.plan_revision_id))
BEGIN SELECT RAISE(ABORT,'Interaction owner is invalid'); END;

CREATE TRIGGER interaction_draft_owner_insert BEFORE INSERT ON interaction_drafts
WHEN NOT EXISTS(SELECT 1 FROM interaction_requests r
    WHERE r.request_id=NEW.request_id AND r.thread_id=NEW.thread_id)
BEGIN SELECT RAISE(ABORT,'Interaction draft owner is invalid'); END;

CREATE TRIGGER interaction_request_identity_update
BEFORE UPDATE OF request_id,thread_id,turn_id,tool_call_id,plan_revision_id,run_id,goal_id,questions_json,created_at
ON interaction_requests
BEGIN SELECT RAISE(ABORT,'Interaction identity is immutable'); END;

CREATE TABLE plan_drafts (
    plan_draft_id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT UNIQUE NOT NULL,
    draft_revision INTEGER NOT NULL CHECK (draft_revision>=0),
    definition_json TEXT NOT NULL CHECK (json_valid(definition_json) AND json_type(definition_json)='object'),
    based_on_plan_revision_id TEXT,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE RESTRICT
);

CREATE TABLE plan_revisions (
    plan_revision_id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number>=1),
    definition_json TEXT NOT NULL CHECK (json_valid(definition_json) AND json_type(definition_json)='object'),
    plan_hash TEXT NOT NULL CHECK (length(plan_hash)=64 AND plan_hash NOT GLOB '*[^0-9a-f]*'),
    created_by TEXT NOT NULL CHECK (created_by IN ('AGENT','USER_UI')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE RESTRICT,
    UNIQUE (plan_id,revision_number),
    UNIQUE (plan_id,plan_revision_id),
    UNIQUE (plan_id,plan_revision_id,plan_hash)
);

CREATE TABLE plan_steps (
    plan_revision_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>=0),
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 240),
    description TEXT NOT NULL,
    required INTEGER NOT NULL CHECK (required IN (0,1)),
    dependency_ids_json TEXT NOT NULL CHECK (json_valid(dependency_ids_json) AND json_type(dependency_ids_json)='array'),
    PRIMARY KEY (plan_revision_id,step_id),
    UNIQUE (plan_revision_id,ordinal),
    FOREIGN KEY (plan_revision_id) REFERENCES plan_revisions(plan_revision_id) ON DELETE RESTRICT
);

CREATE TABLE acceptance_criteria (
    plan_revision_id TEXT NOT NULL,
    criterion_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>=0),
    description TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 2000),
    required INTEGER NOT NULL CHECK (required IN (0,1)),
    PRIMARY KEY (plan_revision_id,criterion_id),
    UNIQUE (plan_revision_id,ordinal),
    FOREIGN KEY (plan_revision_id) REFERENCES plan_revisions(plan_revision_id) ON DELETE RESTRICT
);

CREATE TABLE plan_approvals (
    approval_id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    actor TEXT NOT NULL CHECK (actor='USER_UI'),
    decision TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (plan_id,plan_revision_id,plan_hash) REFERENCES plan_revisions(plan_id,plan_revision_id,plan_hash) ON DELETE RESTRICT,
    UNIQUE (plan_id,plan_revision_id,decision)
);

CREATE TABLE execution_runs (
    run_id TEXT PRIMARY KEY NOT NULL,
    goal_id TEXT,
    plan_id TEXT,
    goal_definition_revision INTEGER,
    plan_revision_id TEXT,
    plan_hash TEXT,
    status TEXT NOT NULL CHECK (status IN ('PREPARED','RUNNING','VERIFYING','PAUSED','COMPLETED','STOPPED')),
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    turn_budget INTEGER NOT NULL DEFAULT 32 CHECK (turn_budget BETWEEN 1 AND 256),
    turns_used INTEGER NOT NULL DEFAULT 0 CHECK (turns_used BETWEEN 0 AND turn_budget),
    pause_requested INTEGER NOT NULL DEFAULT 0 CHECK (pause_requested IN (0,1)),
    max_model_rounds INTEGER CHECK (max_model_rounds>0),
    max_tool_calls INTEGER CHECK (max_tool_calls>=0),
    wall_budget_millis INTEGER CHECK (wall_budget_millis>0),
    used_model_rounds INTEGER NOT NULL DEFAULT 0 CHECK (used_model_rounds>=0),
    used_tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (used_tool_calls>=0),
    used_active_millis INTEGER NOT NULL DEFAULT 0 CHECK (used_active_millis>=0),
    started_at TEXT,
    completed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (goal_id,goal_definition_revision) REFERENCES goal_definition_revisions(goal_id,revision_number) ON DELETE RESTRICT,
    FOREIGN KEY (plan_id,plan_revision_id,plan_hash) REFERENCES plan_revisions(plan_id,plan_revision_id,plan_hash) ON DELETE RESTRICT,
    CHECK (goal_id IS NOT NULL OR plan_id IS NOT NULL),
    CHECK ((goal_id IS NULL)=(goal_definition_revision IS NULL)),
    CHECK ((plan_id IS NULL)=(plan_revision_id IS NULL AND plan_hash IS NULL)),
    CHECK ((status IN ('COMPLETED','STOPPED'))=(completed_at IS NOT NULL))
);


-- admission intent 先于 Turn 创建，turn_id 不建立即时 FK，以便崩溃后判定未派发意图。
CREATE TABLE plan_turn_claims (
    run_id TEXT NOT NULL,
    turn_id TEXT NOT NULL UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal>=1),
    claimed_at TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'CLAIMED' CHECK (state IN ('CLAIMED','SETTLED','ABANDONED')),
    PRIMARY KEY (run_id,ordinal),
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_plan_turn_claim_live ON plan_turn_claims(run_id) WHERE state='CLAIMED';

-- 同一证据输入只允许一次验收请求；证据改变后允许新的验收，UNKNOWN 不自动重试。
CREATE TABLE plan_evaluation_requests (
    request_id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    owner_thread_id TEXT NOT NULL,
    input_digest TEXT NOT NULL CHECK (length(input_digest)=64 AND input_digest NOT GLOB '*[^0-9a-f]*'),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    outcome TEXT NOT NULL CHECK (outcome IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    certainty TEXT NOT NULL CHECK (certainty IN ('UNKNOWN','KNOWN')),
    verdict TEXT CHECK (verdict IS NULL OR verdict IN ('MET','NOT_MET','INCONCLUSIVE')),
    criteria_json TEXT CHECK (criteria_json IS NULL OR (json_valid(criteria_json) AND json_type(criteria_json)='array')),
    summary TEXT CHECK (summary IS NULL OR length(summary) BETWEEN 1 AND 4000),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens>=0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens>=0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens>=input_tokens+output_tokens),
    started_at TEXT NOT NULL,
    completed_at TEXT,
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_revision_id) REFERENCES plan_revisions(plan_revision_id) ON DELETE RESTRICT,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    FOREIGN KEY (owner_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    UNIQUE (plan_id,plan_revision_id,run_id,input_digest),
    CHECK ((certainty='UNKNOWN')=(input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)),
    CHECK ((certainty='KNOWN')=(input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL)),
    CHECK ((outcome='RUNNING')=(completed_at IS NULL)),
    CHECK ((outcome='SUCCEEDED')=(verdict IS NOT NULL AND criteria_json IS NOT NULL AND summary IS NOT NULL))
);
CREATE INDEX idx_plan_evaluation_requests_run ON plan_evaluation_requests(plan_id,run_id,outcome);
CREATE TRIGGER plan_evaluation_requests_immutable_identity
BEFORE UPDATE OF request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,profile_json,started_at
ON plan_evaluation_requests
BEGIN SELECT RAISE(ABORT,'Plan evaluator request identity is immutable'); END;

CREATE TABLE goal_plan_links (
    goal_id TEXT NOT NULL,
    link_revision INTEGER NOT NULL CHECK (link_revision>=1),
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    attached_at TEXT NOT NULL,
    detached_at TEXT,
    PRIMARY KEY (goal_id,link_revision),
    FOREIGN KEY (goal_id) REFERENCES goals(goal_id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_id,plan_revision_id,plan_hash) REFERENCES plan_revisions(plan_id,plan_revision_id,plan_hash) ON DELETE RESTRICT
);

CREATE TABLE plan_step_executions (
    run_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING','READY','RUNNING','BLOCKED','SUCCEEDED','FAILED','SKIPPED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    failure_signature TEXT,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (run_id,step_id),
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_revision_id,step_id) REFERENCES plan_steps(plan_revision_id,step_id) ON DELETE RESTRICT,
    CHECK (status!='RUNNING' OR (started_at IS NOT NULL AND completed_at IS NULL)),
    CHECK ((status IN ('SUCCEEDED','FAILED','SKIPPED'))=(completed_at IS NOT NULL))
);

CREATE TABLE acceptance_evidence (
    evidence_id TEXT PRIMARY KEY NOT NULL,
    goal_id TEXT,
    plan_id TEXT,
    goal_definition_revision INTEGER,
    run_id TEXT NOT NULL,
    plan_revision_id TEXT,
    criterion_id TEXT,
    step_id TEXT,
    source_type TEXT NOT NULL CHECK (source_type IN ('TOOL_RESULT','TEST_REPORT','BUILD_ARTIFACT','REPOSITORY_STATE','UI_ASSERTION','USER_ACCEPTANCE')),
    source_id TEXT NOT NULL,
    summary TEXT NOT NULL CHECK (length(summary) BETWEEN 1 AND 4000),
    digest TEXT NOT NULL CHECK (length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
    observed_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    CHECK (goal_id IS NOT NULL OR plan_id IS NOT NULL),
    CHECK ((goal_id IS NULL)=(goal_definition_revision IS NULL)),
    CHECK (plan_revision_id IS NULL OR plan_id IS NOT NULL)
);

CREATE TABLE goal_tool_attempts (
    tool_attempt_id TEXT PRIMARY KEY NOT NULL,
    goal_id TEXT,
    plan_id TEXT,
    goal_definition_revision INTEGER,
    run_id TEXT NOT NULL,
    plan_revision_id TEXT,
    step_id TEXT,
    attempt INTEGER NOT NULL CHECK (attempt>=1),
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    side_effect INTEGER NOT NULL CHECK (side_effect IN (0,1)),
    state TEXT NOT NULL CHECK (state IN ('PREPARED','STARTED','SUCCEEDED','FAILED','UNKNOWN')),
    request_digest TEXT NOT NULL CHECK (length(request_digest)=64 AND request_digest NOT GLOB '*[^0-9a-f]*'),
    result_digest TEXT CHECK (
        result_digest IS NULL OR (length(result_digest)=64 AND result_digest NOT GLOB '*[^0-9a-f]*')
    ),
    prepared_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id,call_id) REFERENCES tools(turn_id,call_id) ON DELETE RESTRICT,
    CHECK (goal_id IS NOT NULL OR plan_id IS NOT NULL),
    CHECK ((goal_id IS NULL)=(goal_definition_revision IS NULL)),
    CHECK ((state='PREPARED')=(started_at IS NULL AND completed_at IS NULL AND result_digest IS NULL)),
    CHECK (state!='STARTED' OR (started_at IS NOT NULL AND completed_at IS NULL AND result_digest IS NULL)),
    CHECK (state NOT IN ('SUCCEEDED','FAILED') OR
        (started_at IS NOT NULL AND completed_at IS NOT NULL AND result_digest IS NOT NULL)),
    CHECK (state!='UNKNOWN' OR (started_at IS NOT NULL AND completed_at IS NOT NULL)),
    UNIQUE (run_id,call_id,attempt)
);

CREATE TABLE goal_evaluations (
    evaluation_id TEXT PRIMARY KEY NOT NULL,
    goal_id TEXT NOT NULL,
    goal_definition_revision INTEGER NOT NULL,
    run_id TEXT NOT NULL,
    plan_revision_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('REQUESTED','RUNNING','COMPLETED','FAILED')),
    verdict TEXT CHECK (verdict IN ('MET','NOT_MET','INCONCLUSIVE')),
    criteria_json TEXT CHECK (criteria_json IS NULL OR (json_valid(criteria_json) AND json_type(criteria_json)='array')),
    summary TEXT,
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    model_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    error_code TEXT,
    requested_at TEXT NOT NULL,
    completed_at TEXT,
    FOREIGN KEY (goal_id,goal_definition_revision) REFERENCES goal_definition_revisions(goal_id,revision_number) ON DELETE RESTRICT,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    CHECK ((status IN ('COMPLETED','FAILED'))=(completed_at IS NOT NULL)),
    CHECK ((status='COMPLETED')=(verdict IS NOT NULL)),
    CHECK ((status='COMPLETED')=(summary IS NOT NULL))
);

CREATE TABLE goal_events (
    event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT UNIQUE NOT NULL,
    goal_id TEXT NOT NULL,
    goal_revision INTEGER NOT NULL CHECK (goal_revision>=0),
    kind TEXT NOT NULL CHECK (kind IN ('CHANGED','ACTIVITY','INPUT_REQUESTED')),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json) AND json_type(payload_json)='object'),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    created_at TEXT NOT NULL,
    FOREIGN KEY (goal_id) REFERENCES goals(goal_id) ON DELETE RESTRICT,
    UNIQUE (goal_id,idempotency_key)
);

CREATE TABLE plan_events (
    event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT UNIQUE NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision INTEGER NOT NULL CHECK (plan_revision>=0),
    activity TEXT NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    created_at TEXT NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE RESTRICT,
    UNIQUE (plan_id,idempotency_key)
);

CREATE TABLE goal_continuation_leases (
    lease_id TEXT PRIMARY KEY NOT NULL,
    goal_id TEXT NOT NULL,
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    fencing_token INTEGER NOT NULL CHECK (fencing_token>=1),
    state TEXT NOT NULL CHECK (state IN ('HELD','RELEASED','ABANDONED')),
    acquired_at TEXT NOT NULL,
    heartbeat_at TEXT NOT NULL,
    released_at TEXT,
    FOREIGN KEY (goal_id) REFERENCES goals(goal_id) ON DELETE RESTRICT,
    UNIQUE (goal_id,fencing_token),
    CHECK ((state='HELD')=(released_at IS NULL)),
    CHECK ((state IN ('RELEASED','ABANDONED'))=(released_at IS NOT NULL))
);

-- INDEXES

CREATE INDEX idx_threads_workspace_updated ON threads(workspace_id, updated_at DESC, thread_id DESC);

CREATE INDEX idx_attachments_draft_expiry
    ON attachments(status,expires_at,attachment_id) WHERE status='DRAFT';

CREATE INDEX idx_attachments_blob_active
    ON attachments(blob_sha256,status) WHERE status IN ('DRAFT','BOUND');

CREATE INDEX idx_timeline_messages_thread_created
    ON timeline_messages(thread_id, created_at, item_id);

CREATE INDEX idx_approvals_pending ON approvals(thread_id,expires_at) WHERE decision IS NULL;

CREATE INDEX idx_tools_recovery ON tools(state,thread_id,turn_id);

CREATE INDEX idx_threads_workspace_active_pinned
    ON threads(workspace_id, pinned_at DESC, updated_at DESC, thread_id DESC)
    WHERE archived_at IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_thread_lineage_root ON thread_lineage(root_thread_id, depth, created_at, child_thread_id);

CREATE INDEX idx_thread_lineage_parent ON thread_lineage(parent_thread_id, created_at, child_thread_id);

CREATE INDEX idx_task_mailbox_target_pending
    ON task_mailbox(target_thread_id, state, mailbox_sequence);

CREATE INDEX idx_task_mailbox_root_sequence
    ON task_mailbox(root_thread_id, mailbox_sequence);

CREATE INDEX idx_task_activities_root_sequence
    ON task_activities(root_thread_id, activity_sequence);

CREATE INDEX idx_task_activities_task_sequence
    ON task_activities(task_thread_id, activity_sequence);

CREATE INDEX idx_task_projections_root_state
    ON task_projections(root_thread_id, state, updated_at DESC, task_thread_id);

CREATE UNIQUE INDEX uq_workspace_write_claim_held
    ON workspace_write_claims(workspace_id) WHERE state='HELD';

CREATE INDEX idx_workspace_write_claim_fifo
    ON workspace_write_claims(workspace_id, state, claim_sequence);

CREATE INDEX idx_message_attachments_attachment
    ON message_attachments(attachment_id, message_id);

CREATE UNIQUE INDEX uq_pending_inputs_turn_priority
    ON pending_inputs(turn_id,priority_sequence) WHERE priority_sequence IS NOT NULL;

CREATE INDEX idx_pending_inputs_queue
    ON pending_inputs(turn_id,state,kind,priority_sequence,input_sequence);

CREATE INDEX idx_pending_input_attachments_attachment
    ON pending_input_attachments(attachment_id, input_id);

CREATE INDEX idx_turns_thread_sequence ON turns(thread_id,turn_sequence);

CREATE UNIQUE INDEX uq_turns_one_active ON turns(thread_id)
    WHERE state IN ('RUNNING','WAITING_APPROVAL');

CREATE INDEX idx_turns_parent_turn ON turns(parent_turn_id,turn_sequence);

CREATE INDEX idx_turns_root_turn ON turns(root_turn_id,turn_sequence);

CREATE INDEX idx_tool_bindings_batch ON tool_bindings(turn_id,batch_id,call_id);

CREATE UNIQUE INDEX uq_goals_owner_nonterminal ON goals(owner_thread_id) WHERE status IN ('ACTIVE','PAUSED');

CREATE INDEX idx_goals_continuation ON goals(status,phase,recovery_required,updated_at,goal_id);

CREATE INDEX idx_goals_owner_terminal ON goals(owner_thread_id,updated_at,goal_id) WHERE status IN ('ACHIEVED','STOPPED');

CREATE INDEX idx_plans_owner ON plans(owner_thread_id,updated_at,plan_id);

CREATE UNIQUE INDEX uq_plans_owner_nonterminal ON plans(owner_thread_id)
    WHERE status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','PAUSED');

CREATE UNIQUE INDEX uq_execution_runs_goal_live ON execution_runs(goal_id) WHERE goal_id IS NOT NULL AND status IN ('PREPARED','RUNNING','VERIFYING');

CREATE UNIQUE INDEX uq_execution_runs_plan_live ON execution_runs(plan_id) WHERE goal_id IS NULL AND status IN ('PREPARED','RUNNING','VERIFYING','PAUSED');

CREATE UNIQUE INDEX uq_goal_plan_link_active ON goal_plan_links(goal_id) WHERE detached_at IS NULL;

CREATE UNIQUE INDEX uq_acceptance_evidence_source ON acceptance_evidence(
  run_id,source_type,source_id,digest,COALESCE(criterion_id,''),COALESCE(step_id,''));

CREATE INDEX idx_goal_tool_attempts_recovery ON goal_tool_attempts(state,process_generation,side_effect,prepared_at,tool_attempt_id);

CREATE UNIQUE INDEX uq_goal_evaluations_run_live ON goal_evaluations(run_id) WHERE status IN ('REQUESTED','RUNNING');

CREATE INDEX idx_goal_events_page ON goal_events(goal_id,event_sequence);

CREATE UNIQUE INDEX uq_goal_continuation_held ON goal_continuation_leases(goal_id) WHERE state='HELD';

-- TRIGGERS

CREATE TRIGGER messages_immutable_delete BEFORE DELETE ON messages
WHEN NOT EXISTS(
    SELECT 1 FROM temporary_side_chats c
    WHERE c.thread_id=OLD.thread_id AND c.state='CLOSING'
)
BEGIN SELECT RAISE(ABORT, 'messages are immutable'); END;

CREATE TRIGGER context_checkpoints_immutable_update BEFORE UPDATE ON context_checkpoints
BEGIN SELECT RAISE(ABORT, 'context checkpoints are immutable'); END;

CREATE TRIGGER context_checkpoints_immutable_delete BEFORE DELETE ON context_checkpoints
WHEN NOT EXISTS(
    SELECT 1 FROM temporary_side_chats c
    WHERE c.thread_id=OLD.thread_id AND c.state='CLOSING'
)
BEGIN SELECT RAISE(ABORT, 'context checkpoints are immutable'); END;

CREATE TRIGGER messages_immutable_update BEFORE UPDATE ON messages
BEGIN SELECT RAISE(ABORT, 'messages are immutable'); END;

CREATE TRIGGER task_context_seeds_immutable_update BEFORE UPDATE ON task_context_seeds
BEGIN SELECT RAISE(ABORT,'task context seeds are immutable'); END;

CREATE TRIGGER thread_lineage_immutable_update BEFORE UPDATE ON thread_lineage
BEGIN SELECT RAISE(ABORT,'thread lineage is immutable'); END;

CREATE TRIGGER task_activities_immutable_update BEFORE UPDATE ON task_activities
BEGIN SELECT RAISE(ABORT,'task activities are append only'); END;

CREATE TRIGGER usage_unknown_only_insert BEFORE INSERT ON usage
WHEN NEW.certainty<>'UNKNOWN'
BEGIN SELECT RAISE(ABORT,'usage request must start as UNKNOWN'); END;

CREATE TRIGGER usage_settlement_only_update BEFORE UPDATE ON usage
WHEN NOT (
    OLD.certainty='UNKNOWN'
    AND NEW.certainty='KNOWN'
    AND NEW.usage_id=OLD.usage_id AND NEW.request_id=OLD.request_id
    AND NEW.thread_id=OLD.thread_id AND NEW.turn_id=OLD.turn_id
    AND NEW.model_round=OLD.model_round AND NEW.request_ordinal=OLD.request_ordinal
    AND NEW.purpose=OLD.purpose
    AND NEW.profile_json=OLD.profile_json AND NEW.created_at>=OLD.created_at
    AND NEW.input_tokens IS NOT NULL AND NEW.output_tokens IS NOT NULL
    AND NEW.total_tokens>=NEW.input_tokens+NEW.output_tokens
)
BEGIN SELECT RAISE(ABORT,'usage update is not a request settlement'); END;

CREATE TRIGGER usage_immutable_delete BEFORE DELETE ON usage
WHEN NOT EXISTS(
    SELECT 1 FROM temporary_side_chats c
    WHERE c.thread_id=OLD.thread_id AND c.state='CLOSING'
)
BEGIN SELECT RAISE(ABORT,'usage is immutable'); END;

CREATE TRIGGER tool_bindings_immutable_update BEFORE UPDATE ON tool_bindings
BEGIN SELECT RAISE(ABORT,'Tool bindings are immutable'); END;

CREATE TRIGGER tool_bindings_immutable_delete BEFORE DELETE ON tool_bindings
WHEN NOT EXISTS(
    SELECT 1 FROM turns t
    JOIN temporary_side_chats c ON c.thread_id=t.thread_id
    WHERE t.turn_id=OLD.turn_id AND c.state='CLOSING'
)
BEGIN SELECT RAISE(ABORT,'Tool bindings are immutable'); END;

CREATE TRIGGER turn_internal_context_immutable BEFORE UPDATE ON turn_internal_context
BEGIN SELECT RAISE(ABORT,'Turn internal context is immutable'); END;

CREATE TRIGGER goal_definition_revisions_immutable BEFORE UPDATE ON goal_definition_revisions BEGIN SELECT RAISE(ABORT,'goal definition revisions are immutable'); END;

CREATE TRIGGER goal_acceptance_criteria_immutable BEFORE UPDATE ON goal_acceptance_criteria BEGIN SELECT RAISE(ABORT,'goal acceptance criteria are immutable'); END;

CREATE TRIGGER plan_revisions_immutable_update BEFORE UPDATE ON plan_revisions BEGIN SELECT RAISE(ABORT,'plan revisions are immutable'); END;

CREATE TRIGGER plan_steps_immutable_update BEFORE UPDATE ON plan_steps BEGIN SELECT RAISE(ABORT,'plan steps are immutable'); END;

CREATE TRIGGER acceptance_criteria_immutable_update BEFORE UPDATE ON acceptance_criteria BEGIN SELECT RAISE(ABORT,'acceptance criteria are immutable'); END;

CREATE TRIGGER plan_approvals_immutable_update BEFORE UPDATE ON plan_approvals BEGIN SELECT RAISE(ABORT,'plan approvals are immutable'); END;

CREATE TRIGGER acceptance_evidence_immutable_update BEFORE UPDATE ON acceptance_evidence BEGIN SELECT RAISE(ABORT,'acceptance evidence is append only'); END;

CREATE TRIGGER goal_events_immutable_update BEFORE UPDATE ON goal_events BEGIN SELECT RAISE(ABORT,'goal events are append only'); END;

CREATE TRIGGER plan_events_immutable_update BEFORE UPDATE ON plan_events BEGIN SELECT RAISE(ABORT,'plan events are append only'); END;

CREATE TRIGGER plan_step_executions_run_revision_insert BEFORE INSERT ON plan_step_executions
WHEN NOT EXISTS(
  SELECT 1 FROM execution_runs r
  WHERE r.run_id=NEW.run_id AND r.plan_revision_id=NEW.plan_revision_id
)
BEGIN SELECT RAISE(ABORT,'Plan step execution run revision is invalid'); END;

CREATE TRIGGER plan_step_executions_identity_update
BEFORE UPDATE OF run_id,plan_revision_id,step_id ON plan_step_executions
BEGIN SELECT RAISE(ABORT,'Plan step execution identity is immutable'); END;

CREATE TRIGGER pending_inputs_turn_owner_insert BEFORE INSERT ON pending_inputs
WHEN NOT EXISTS(
  SELECT 1 FROM turns t WHERE t.turn_id=NEW.turn_id AND t.thread_id=NEW.thread_id
)
BEGIN SELECT RAISE(ABORT,'Pending input Turn owner is invalid'); END;

CREATE TRIGGER pending_inputs_owner_update
BEFORE UPDATE OF thread_id,turn_id ON pending_inputs
BEGIN SELECT RAISE(ABORT,'Pending input owner is immutable'); END;

CREATE TRIGGER goal_tool_attempts_run_owner_insert BEFORE INSERT ON goal_tool_attempts
WHEN NOT EXISTS(
  SELECT 1 FROM execution_runs r
  WHERE r.run_id=NEW.run_id
    AND r.goal_id IS NEW.goal_id
    AND r.plan_id IS NEW.plan_id
    AND r.goal_definition_revision IS NEW.goal_definition_revision
    AND r.plan_revision_id IS NEW.plan_revision_id
)
BEGIN SELECT RAISE(ABORT,'Goal Tool attempt run owner is invalid'); END;

CREATE TRIGGER goal_tool_attempts_identity_update
BEFORE UPDATE OF tool_attempt_id,goal_id,plan_id,goal_definition_revision,run_id,plan_revision_id,
  step_id,attempt,turn_id,call_id,process_generation,side_effect,request_digest,prepared_at
ON goal_tool_attempts
BEGIN SELECT RAISE(ABORT,'Goal Tool attempt identity is immutable'); END;

CREATE TRIGGER acceptance_evidence_criterion_owner_insert BEFORE INSERT ON acceptance_evidence
WHEN NEW.criterion_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM goal_acceptance_criteria g
  WHERE NEW.goal_id IS NOT NULL AND g.goal_id=NEW.goal_id
    AND g.goal_definition_revision=NEW.goal_definition_revision AND g.criterion_id=NEW.criterion_id
) AND NOT EXISTS(
  SELECT 1 FROM acceptance_criteria p
  WHERE NEW.plan_revision_id IS NOT NULL AND p.plan_revision_id=NEW.plan_revision_id
    AND p.criterion_id=NEW.criterion_id
)
BEGIN SELECT RAISE(ABORT,'acceptance evidence criterion owner is invalid'); END;

CREATE TRIGGER goals_active_run_owner_insert BEFORE INSERT ON goals
WHEN EXISTS(SELECT 1 FROM execution_runs r WHERE r.run_id=NEW.active_run_id AND r.goal_id<>NEW.goal_id)
BEGIN SELECT RAISE(ABORT,'Goal active run owner is invalid'); END;

CREATE TRIGGER goals_active_run_owner_update BEFORE UPDATE OF active_run_id ON goals
WHEN NOT EXISTS(SELECT 1 FROM execution_runs r WHERE r.run_id=NEW.active_run_id AND r.goal_id=NEW.goal_id)
BEGIN SELECT RAISE(ABORT,'Goal active run owner is invalid'); END;

CREATE TRIGGER execution_runs_goal_owner_insert BEFORE INSERT ON execution_runs
WHEN NEW.goal_id IS NOT NULL AND EXISTS(
  SELECT 1 FROM goals g WHERE g.active_run_id=NEW.run_id AND g.goal_id<>NEW.goal_id
)
BEGIN SELECT RAISE(ABORT,'execution run Goal owner is invalid'); END;

CREATE TRIGGER plans_active_identity_insert BEFORE INSERT ON plans
WHEN (NEW.active_plan_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM plan_revisions r WHERE r.plan_revision_id=NEW.active_plan_revision_id AND r.plan_id=NEW.plan_id
)) OR (NEW.active_run_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM execution_runs x WHERE x.run_id=NEW.active_run_id AND x.plan_id=NEW.plan_id AND x.goal_id IS NULL
))
BEGIN SELECT RAISE(ABORT,'Plan active identity owner is invalid'); END;

CREATE TRIGGER plans_active_identity_update BEFORE UPDATE OF active_plan_revision_id,active_run_id ON plans
WHEN (NEW.active_plan_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM plan_revisions r WHERE r.plan_revision_id=NEW.active_plan_revision_id AND r.plan_id=NEW.plan_id
)) OR (NEW.active_run_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM execution_runs x WHERE x.run_id=NEW.active_run_id AND x.plan_id=NEW.plan_id AND x.goal_id IS NULL
))
BEGIN SELECT RAISE(ABORT,'Plan active identity owner is invalid'); END;

INSERT INTO task_process_generation(singleton_id,last_generation) VALUES (1,0);
