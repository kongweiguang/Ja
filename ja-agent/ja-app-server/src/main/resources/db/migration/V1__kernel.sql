-- @author kongweiguang
-- Ja 0.2 fresh baseline. Core schema is read from its original classpath resource by JaFlywayResources.
-- All persisted timestamps use UTC epoch milliseconds; public content belongs to Core typed records.

CREATE TABLE ja_workspace (
 workspace_id TEXT CONSTRAINT pk_ja_workspace PRIMARY KEY NOT NULL,
 root_path TEXT NOT NULL CONSTRAINT uk_ja_workspace_root UNIQUE,
 kind TEXT NOT NULL CHECK(kind IN ('PROJECT','SESSION')),
 display_name TEXT NOT NULL, trust TEXT NOT NULL CHECK(trust IN ('TRUSTED','UNTRUSTED')),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_conversation_thread (
    thread_id TEXT CONSTRAINT pk_ja_conversation_thread_1 PRIMARY KEY NOT NULL,
    owner_user_id TEXT NOT NULL CHECK(owner_user_id='user_local'),
    temporary_lifecycle_state TEXT CHECK(temporary_lifecycle_state IS NULL OR temporary_lifecycle_state IN ('OPEN','CLOSING')),
    workspace_id TEXT NOT NULL,
    title TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    archived_at_ms INTEGER,
    deleted_at_ms INTEGER,
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    access_mode TEXT NOT NULL CHECK (access_mode IN ('APPROVAL_REQUIRED','FULL_ACCESS')),
    reasoning_level TEXT CHECK (
        reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
    ),
    pinned_at_ms INTEGER,
    last_seen_turn_sequence INTEGER CHECK (last_seen_turn_sequence IS NULL OR last_seen_turn_sequence > 0),
    collaboration_mode TEXT NOT NULL DEFAULT 'DEFAULT' CHECK (collaboration_mode IN ('DEFAULT','PLAN')),
    title_source TEXT NOT NULL CHECK (title_source IN ('PLACEHOLDER','AUTO','MANUAL')),
    CONSTRAINT fk_ja_conversation_thread_1 FOREIGN KEY (workspace_id) REFERENCES ja_workspace(workspace_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer'),
 CHECK(archived_at_ms IS NULL OR typeof(archived_at_ms)='integer'),
 CHECK(deleted_at_ms IS NULL OR typeof(deleted_at_ms)='integer'),
 CHECK(pinned_at_ms IS NULL OR typeof(pinned_at_ms)='integer')
);

CREATE TABLE ja_conversation_turn (
    turn_sequence INTEGER CONSTRAINT pk_ja_conversation_turn_1 PRIMARY KEY AUTOINCREMENT,
    turn_id TEXT CONSTRAINT uk_ja_conversation_turn_1 UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'QUEUED','RUNNING','WAITING_APPROVAL','WAITING_INPUT','SUSPENDED','COMPLETED','FAILED','CANCELLED'
    )),
    mutation_version INTEGER NOT NULL DEFAULT 0 CHECK (mutation_version >= 0),
    requested_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER,
    terminal_summary TEXT,
    error_code TEXT,
    error_message TEXT,
    cancel_requested_at_ms INTEGER,
    cancel_reason TEXT,
    cancel_expected_thread_revision INTEGER,
    cancel_thread_revision INTEGER,
    cancel_turn_mutation_version INTEGER,
    input_queue_revision INTEGER NOT NULL DEFAULT 0 CHECK (input_queue_revision >= 0),
    is_accepting_inputs INTEGER NOT NULL DEFAULT 1 CHECK (is_accepting_inputs IN (0,1)),
    parent_turn_id TEXT,
    root_turn_id TEXT, is_current_path INTEGER NOT NULL DEFAULT 1 CHECK (is_current_path IN (0,1)), source_message_id TEXT,
    CONSTRAINT fk_ja_conversation_turn_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_conversation_turn_2 FOREIGN KEY (parent_turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_conversation_turn_3 FOREIGN KEY (root_turn_id) REFERENCES ja_conversation_turn(turn_id),
    CHECK ((state IN ('COMPLETED','FAILED','CANCELLED')) = (completed_at_ms IS NOT NULL)),
    CHECK ((state NOT IN ('COMPLETED','FAILED','CANCELLED')) = (terminal_summary IS NULL)),
    CHECK (cancel_expected_thread_revision IS NULL OR cancel_expected_thread_revision >= 0),
    CHECK (cancel_thread_revision IS NULL OR cancel_thread_revision >= 0),
    CHECK (cancel_turn_mutation_version IS NULL OR cancel_turn_mutation_version >= 0),
    CHECK ((cancel_requested_at_ms IS NULL)
        = (cancel_reason IS NULL AND cancel_expected_thread_revision IS NULL
            AND cancel_thread_revision IS NULL AND cancel_turn_mutation_version IS NULL))
, CONSTRAINT uk_ja_conversation_turn_owner UNIQUE(turn_id,thread_id),
 CHECK(requested_at_ms IS NULL OR typeof(requested_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer'),
 CHECK(cancel_requested_at_ms IS NULL OR typeof(cancel_requested_at_ms)='integer')
);

CREATE TABLE ja_context_checkpoint (
    checkpoint_id TEXT CONSTRAINT pk_ja_context_checkpoint_1 PRIMARY KEY NOT NULL,
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
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_context_checkpoint_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT uk_ja_context_checkpoint_1 UNIQUE (thread_id, source_revision),
    CHECK (retained_split_json IS NULL OR retained_from_ordinal = through_ordinal),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_conversation_instruction_scope (
    thread_id TEXT NOT NULL,
    relative_directory TEXT NOT NULL,
    discovered_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_conversation_instruction_scope_1 PRIMARY KEY (thread_id, relative_directory),
    CONSTRAINT fk_ja_conversation_instruction_scope_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
 CHECK(discovered_at_ms IS NULL OR typeof(discovered_at_ms)='integer')
);

CREATE TABLE ja_conversation_title_generation (
    generation_id TEXT CONSTRAINT pk_ja_conversation_title_generation_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL CONSTRAINT uk_ja_conversation_title_generation_1 UNIQUE,
    turn_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    config_generation TEXT NOT NULL,
    result TEXT CHECK (result IS NULL OR result IN ('SUCCEEDED','FAILED')),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens>=0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens>=0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens>=0),
    failure_code TEXT,
    claimed_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER,
    CONSTRAINT fk_ja_conversation_title_generation_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_conversation_title_generation_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CHECK ((result IS NULL)=(completed_at_ms IS NULL)),
    CHECK (result IS NULL OR result='FAILED' OR (
        input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL
        AND total_tokens>=input_tokens+output_tokens
    )),
    CHECK (result IS NULL OR result='SUCCEEDED' OR failure_code IS NOT NULL),
    CHECK (result IS NULL OR result='FAILED' OR failure_code IS NULL),
    CHECK ((input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)
        OR (input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL
            AND total_tokens>=input_tokens+output_tokens)),
 CHECK(claimed_at_ms IS NULL OR typeof(claimed_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer')
);

CREATE TABLE ja_conversation_lineage (
    child_thread_id TEXT CONSTRAINT pk_ja_conversation_lineage_1 PRIMARY KEY NOT NULL,
    parent_thread_id TEXT NOT NULL,
    root_thread_id TEXT NOT NULL,
    origin_turn_id TEXT,
    task_name TEXT NOT NULL CHECK (length(task_name) BETWEEN 1 AND 96),
    depth INTEGER NOT NULL CHECK (depth BETWEEN 1 AND 4),
    task_kind TEXT NOT NULL CHECK (task_kind IN ('SIDE_TASK','SUBAGENT')),
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('INDEPENDENT','ATTACHED')),
    context_seed_id TEXT NOT NULL CONSTRAINT uk_ja_conversation_lineage_1 UNIQUE,
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_conversation_lineage_1 FOREIGN KEY (child_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_conversation_lineage_2 FOREIGN KEY (parent_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_conversation_lineage_3 FOREIGN KEY (root_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_conversation_lineage_4 FOREIGN KEY (origin_turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_conversation_lineage_5 FOREIGN KEY (context_seed_id) REFERENCES ja_task_context_seed(context_seed_id),
    CHECK (child_thread_id<>parent_thread_id AND child_thread_id<>root_thread_id),
    CHECK ((task_kind='SIDE_TASK' AND lifecycle='INDEPENDENT') OR
           (task_kind='SUBAGENT' AND lifecycle='ATTACHED')),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_task_side_chat_close_receipt (
    root_thread_id TEXT CONSTRAINT pk_ja_task_side_chat_close_receipt_1 PRIMARY KEY NOT NULL
        CHECK (length(root_thread_id) BETWEEN 5 AND 128),
    workspace_id TEXT NOT NULL,
    owner_root_thread_id TEXT NOT NULL CHECK (length(owner_root_thread_id) BETWEEN 5 AND 128),
    closed_thread_count INTEGER NOT NULL CHECK (closed_thread_count > 0
        AND typeof(closed_thread_count)='integer'),
    closed_at_ms INTEGER NOT NULL CHECK (closed_at_ms >= 0 AND typeof(closed_at_ms)='integer'),
    CONSTRAINT fk_ja_task_side_chat_close_receipt_1
        FOREIGN KEY (workspace_id) REFERENCES ja_workspace(workspace_id)
);

CREATE TABLE ja_task_side_chat_close_member (
    root_thread_id TEXT NOT NULL,
    member_ordinal INTEGER NOT NULL CHECK (member_ordinal >= 0 AND typeof(member_ordinal)='integer'),
    thread_id TEXT NOT NULL CHECK (length(thread_id) BETWEEN 5 AND 128),
    CONSTRAINT pk_ja_task_side_chat_close_member_1 PRIMARY KEY (root_thread_id, member_ordinal),
    CONSTRAINT uk_ja_task_side_chat_close_member_1 UNIQUE (root_thread_id, thread_id),
    CONSTRAINT fk_ja_task_side_chat_close_member_1
        FOREIGN KEY (root_thread_id) REFERENCES ja_task_side_chat_close_receipt(root_thread_id)
);

CREATE INDEX idx_ja_task_side_chat_close_receipt_workspace
    ON ja_task_side_chat_close_receipt(workspace_id, root_thread_id);

CREATE TABLE ja_conversation_subagent_policy (
    thread_id TEXT CONSTRAINT pk_ja_conversation_subagent_policy_1 PRIMARY KEY NOT NULL,
    is_enabled INTEGER NOT NULL CHECK (is_enabled IN (0,1)),
    provider_id TEXT,
    model_id TEXT,
    created_at_ms INTEGER NOT NULL, reasoning_level TEXT CHECK (
    reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
    AND (provider_id IS NOT NULL OR reasoning_level IS NULL)
),
    CONSTRAINT fk_ja_conversation_subagent_policy_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CHECK ((provider_id IS NULL) = (model_id IS NULL)),
    CHECK (provider_id IS NULL OR (provider_id GLOB 'provider_*' AND model_id GLOB 'model_*')),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_attachment_blob (
    sha256 TEXT CONSTRAINT pk_ja_attachment_blob_1 PRIMARY KEY NOT NULL CHECK (
        length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 104857600),
    media_kind TEXT NOT NULL CHECK (media_kind IN ('TEXT','IMAGE','PDF','BINARY')),
    media_type TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_attachment (
    attachment_id TEXT CONSTRAINT pk_ja_attachment_1 PRIMARY KEY NOT NULL,
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
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    bound_at_ms INTEGER,
    discarded_at_ms INTEGER,
    expired_at_ms INTEGER,
    CONSTRAINT fk_ja_attachment_1 FOREIGN KEY (workspace_id) REFERENCES ja_workspace(workspace_id),
    CONSTRAINT fk_ja_attachment_2 FOREIGN KEY (blob_sha256) REFERENCES ja_attachment_blob(sha256),
    CHECK ((status='BOUND')=(bound_at_ms IS NOT NULL)),
    CHECK ((status='DISCARDED')=(discarded_at_ms IS NOT NULL)),
    CHECK ((status='EXPIRED')=(expired_at_ms IS NOT NULL)),
    CHECK ((status IN ('DRAFT','BOUND'))=(blob_sha256 IS NOT NULL)),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(expires_at_ms IS NULL OR typeof(expires_at_ms)='integer'),
 CHECK(bound_at_ms IS NULL OR typeof(bound_at_ms)='integer'),
 CHECK(discarded_at_ms IS NULL OR typeof(discarded_at_ms)='integer'),
 CHECK(expired_at_ms IS NULL OR typeof(expired_at_ms)='integer')
);

CREATE TABLE "ja_message_attachment" (
    message_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_message_attachment_1 PRIMARY KEY (message_id, ordinal),
    CONSTRAINT fk_ja_message_attachment_1 FOREIGN KEY (message_id) REFERENCES ja_conversation_message(message_id),
    CONSTRAINT fk_ja_message_attachment_2 FOREIGN KEY (attachment_id) REFERENCES ja_attachment(attachment_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_tool_artifact (
    artifact_id TEXT CONSTRAINT pk_ja_tool_artifact_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    content TEXT NOT NULL,
    character_length INTEGER NOT NULL CHECK (character_length >= 0),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_tool_artifact_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_tool_artifact_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_tool_artifact_3 FOREIGN KEY (thread_id,turn_id,call_id) REFERENCES ja_tool_call(thread_id,turn_id,call_id),
    CONSTRAINT uk_ja_tool_artifact_1 UNIQUE (thread_id,turn_id,call_id,artifact_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE "ja_tool_call" (
    call_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    tool_name TEXT NOT NULL,
    side_effect TEXT NOT NULL CHECK (side_effect IN ('READ_ONLY','EXTERNAL')),
    presentation_json TEXT NOT NULL CHECK (json_valid(presentation_json) AND json_type(presentation_json)='object'),
    artifact_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('PREPARED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_tool_call PRIMARY KEY(thread_id,turn_id,call_id),
    CONSTRAINT fk_ja_tool_call_turn_owner FOREIGN KEY (turn_id,thread_id) REFERENCES ja_conversation_turn(turn_id,thread_id),
    CONSTRAINT uk_ja_tool_call_1 UNIQUE (turn_id,ordinal),CONSTRAINT uk_ja_tool_call_2 UNIQUE (turn_id,call_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_tool_binding (
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
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_tool_binding_1 PRIMARY KEY (turn_id,call_id),
    CONSTRAINT fk_ja_tool_binding_1 FOREIGN KEY (turn_id,call_id) REFERENCES ja_tool_call(turn_id,call_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_tool_recovery (
    recovery_id TEXT CONSTRAINT pk_ja_tool_recovery_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    evidence_kind TEXT NOT NULL CHECK (evidence_kind IN ('NONE','FILE_TEXT')),
    state TEXT NOT NULL CHECK (state IN ('PENDING','VERIFYING','VERIFIED','RETRIED','SKIPPED')),
    recovery_revision INTEGER NOT NULL DEFAULT 1 CHECK (recovery_revision >= 1),
    target_relative_path TEXT,
    expected_after_sha256 TEXT CHECK (
        expected_after_sha256 IS NULL OR (
            length(expected_after_sha256)=64 AND expected_after_sha256 NOT GLOB '*[^0-9a-f]*'
        )
    ),
    expected_after_bytes INTEGER CHECK (expected_after_bytes IS NULL OR expected_after_bytes >= 0),
    operation_key TEXT CONSTRAINT uk_ja_tool_recovery_1 UNIQUE,
    verified_at_ms INTEGER,
    resolved_at_ms INTEGER,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT uk_ja_tool_recovery_call UNIQUE(thread_id,turn_id,call_id),
    CONSTRAINT fk_ja_tool_recovery_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_tool_recovery_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_tool_recovery_3 FOREIGN KEY (thread_id,turn_id,call_id) REFERENCES ja_tool_call(thread_id,turn_id,call_id),
    CHECK ((evidence_kind='NONE') = (
        target_relative_path IS NULL AND expected_after_sha256 IS NULL AND expected_after_bytes IS NULL
    )),
    CHECK ((evidence_kind='FILE_TEXT') = (
        target_relative_path IS NOT NULL AND expected_after_sha256 IS NOT NULL AND expected_after_bytes IS NOT NULL
    )),
    CHECK ((state='VERIFIED') = (verified_at_ms IS NOT NULL AND resolved_at_ms IS NOT NULL)),
    CHECK ((state IN ('RETRIED','SKIPPED')) = (resolved_at_ms IS NOT NULL)),
 CHECK(verified_at_ms IS NULL OR typeof(verified_at_ms)='integer'),
 CHECK(resolved_at_ms IS NULL OR typeof(resolved_at_ms)='integer'),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_tool_recovery_attempt (
    recovery_attempt_id TEXT CONSTRAINT pk_ja_tool_recovery_attempt_1 PRIMARY KEY NOT NULL,
    recovery_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    disposition TEXT NOT NULL CHECK (disposition IN ('STARTUP','VERIFY','RETRY','SKIP')),
    operation_key TEXT,
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_tool_recovery_attempt_1 FOREIGN KEY (recovery_id) REFERENCES ja_tool_recovery(recovery_id),
    CONSTRAINT uk_ja_tool_recovery_attempt_1 UNIQUE (recovery_id, attempt_number),
    CONSTRAINT uk_ja_tool_recovery_attempt_2 UNIQUE (recovery_id, operation_key),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_approval (
    approval_id TEXT CONSTRAINT pk_ja_approval_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    decision TEXT CHECK (decision IS NULL OR decision IN ('APPROVE','DENY')),
    requested_at_ms INTEGER NOT NULL,
    resolved_at_ms INTEGER,
    expires_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_approval_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_approval_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_approval_3 FOREIGN KEY (thread_id,turn_id,call_id) REFERENCES ja_tool_call(thread_id,turn_id,call_id),
    CONSTRAINT uk_ja_approval_1 UNIQUE (turn_id,call_id),
    CHECK ((decision IS NULL) = (resolved_at_ms IS NULL)),
 CHECK(requested_at_ms IS NULL OR typeof(requested_at_ms)='integer'),
 CHECK(resolved_at_ms IS NULL OR typeof(resolved_at_ms)='integer'),
 CHECK(expires_at_ms IS NULL OR typeof(expires_at_ms)='integer')
);

CREATE TABLE ja_turn_change_set (
    turn_id TEXT CONSTRAINT pk_ja_turn_change_set_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    change_set_json TEXT NOT NULL CHECK (json_valid(change_set_json) AND json_type(change_set_json)='object'),
    artifact_id TEXT,
    committed_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_turn_change_set_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_turn_change_set_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_turn_change_set_3 FOREIGN KEY (workspace_id) REFERENCES ja_workspace(workspace_id),
 CHECK(committed_at_ms IS NULL OR typeof(committed_at_ms)='integer')
);

CREATE TABLE ja_change_set_artifact (
    artifact_id TEXT CONSTRAINT pk_ja_change_set_artifact_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    content TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    byte_length INTEGER NOT NULL CHECK (byte_length BETWEEN 0 AND 2097152),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_change_set_artifact_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_change_set_artifact_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT uk_ja_change_set_artifact_1 UNIQUE (thread_id,turn_id,artifact_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_turn_input (
    input_sequence INTEGER CONSTRAINT pk_ja_turn_input_1 PRIMARY KEY AUTOINCREMENT,
    input_id TEXT CONSTRAINT uk_ja_turn_input_1 UNIQUE NOT NULL,
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
    is_issue_retryable INTEGER CHECK (is_issue_retryable IS NULL OR is_issue_retryable IN (0,1)),
    input_revision INTEGER NOT NULL DEFAULT 1 CHECK (input_revision >= 1),
    priority_sequence INTEGER,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    resolved_at_ms INTEGER,
    CONSTRAINT fk_ja_turn_input_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_turn_input_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CHECK ((state='PENDING') = (resolved_at_ms IS NULL)),
    CHECK ((kind='STEERING') = (priority_sequence IS NOT NULL)),
    CHECK (priority_sequence IS NULL OR priority_sequence >= 1),
    CHECK ((validation_status='PENDING'
            AND issue_error_code IS NULL AND issue_message IS NULL AND is_issue_retryable IS NULL)
        OR (validation_status='NEEDS_ATTENTION'
            AND issue_error_code IS NOT NULL AND issue_message IS NOT NULL AND is_issue_retryable IS NOT NULL)),
    CHECK (state='PENDING' OR validation_status='PENDING'),
    CHECK (state!='PENDING' OR (length(content_json) BETWEEN 1 AND 4194304
        AND length(CAST(content_json AS BLOB)) BETWEEN 1 AND 524288)),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer'),
 CHECK(resolved_at_ms IS NULL OR typeof(resolved_at_ms)='integer')
);

CREATE TABLE ja_turn_input_attachment (
    input_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL CONSTRAINT uk_ja_turn_input_attachment_1 UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_turn_input_attachment_1 PRIMARY KEY (input_id, ordinal),
    CONSTRAINT fk_ja_turn_input_attachment_1 FOREIGN KEY (input_id) REFERENCES ja_turn_input(input_id),
    CONSTRAINT fk_ja_turn_input_attachment_2 FOREIGN KEY (attachment_id) REFERENCES ja_attachment(attachment_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE "ja_turn_usage" (
    usage_id TEXT CONSTRAINT pk_ja_turn_usage_1 PRIMARY KEY NOT NULL,
    model_request_id TEXT CONSTRAINT uk_ja_turn_usage_2 UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    model_round INTEGER NOT NULL CHECK (model_round >= 1),
    model_request_ordinal INTEGER NOT NULL CHECK (model_request_ordinal >= 1),
    purpose TEXT NOT NULL CHECK (purpose IN ('ASSISTANT','SUMMARY')),
    certainty TEXT NOT NULL CHECK (certainty IN ('KNOWN','UNKNOWN')),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens >= 0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens >= 0),
    created_at_ms INTEGER NOT NULL,
    cache_read_tokens INTEGER CHECK (cache_read_tokens IS NULL OR cache_read_tokens >= 0),
    cache_write_tokens INTEGER CHECK (cache_write_tokens IS NULL OR cache_write_tokens >= 0),
    new_input_tokens INTEGER CHECK (new_input_tokens IS NULL OR new_input_tokens >= 0),
    input_accounting TEXT NOT NULL DEFAULT 'UNKNOWN'
      CHECK (input_accounting IN ('INPUT_EXCLUDES_CACHE','INPUT_INCLUDES_CACHE','UNKNOWN')), estimated_context_tokens INTEGER
        CHECK (estimated_context_tokens IS NULL OR estimated_context_tokens >= 0),
    CONSTRAINT fk_ja_turn_usage_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_turn_usage_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT uk_ja_turn_usage_1 UNIQUE (turn_id,model_request_ordinal),
    CHECK ((certainty='UNKNOWN' AND input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)
        OR (certainty='KNOWN' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL
            AND total_tokens IS NOT NULL AND total_tokens >= input_tokens + output_tokens)),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_turn_internal_context (
    turn_id TEXT CONSTRAINT pk_ja_turn_internal_context_1 PRIMARY KEY NOT NULL,
    origin TEXT NOT NULL CHECK (origin='PLAN_EXECUTION'),
    context_json TEXT NOT NULL CHECK (
        json_valid(context_json) AND json_type(context_json)='object'
        AND json_extract(context_json,'$.kind')=origin
    ),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_turn_internal_context_1 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_workspace_write_claim (
    claim_sequence INTEGER CONSTRAINT pk_ja_workspace_write_claim_1 PRIMARY KEY AUTOINCREMENT,
    claim_id TEXT CONSTRAINT uk_ja_workspace_write_claim_2 UNIQUE NOT NULL,
    workspace_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    process_generation INTEGER NOT NULL CHECK (process_generation >= 1),
    fencing_token INTEGER NOT NULL CHECK (fencing_token >= 1),
    state TEXT NOT NULL CHECK (state IN ('WAITING','HELD','RELEASED','ABANDONED')),
    requested_at_ms INTEGER NOT NULL,
    acquired_at_ms INTEGER,
    heartbeat_at_ms INTEGER,
    released_at_ms INTEGER,
    CONSTRAINT fk_ja_workspace_write_claim_1 FOREIGN KEY (workspace_id) REFERENCES ja_workspace(workspace_id),
    CONSTRAINT fk_ja_workspace_write_claim_2 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_workspace_write_claim_3 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT uk_ja_workspace_write_claim_1 UNIQUE (workspace_id, fencing_token),
    CHECK ((state='WAITING') = (acquired_at_ms IS NULL AND heartbeat_at_ms IS NULL AND released_at_ms IS NULL)),
    CHECK ((state='HELD') = (acquired_at_ms IS NOT NULL AND heartbeat_at_ms IS NOT NULL AND released_at_ms IS NULL)),
    CHECK ((state IN ('RELEASED','ABANDONED')) = (released_at_ms IS NOT NULL)),
 CHECK(requested_at_ms IS NULL OR typeof(requested_at_ms)='integer'),
 CHECK(acquired_at_ms IS NULL OR typeof(acquired_at_ms)='integer'),
 CHECK(heartbeat_at_ms IS NULL OR typeof(heartbeat_at_ms)='integer'),
 CHECK(released_at_ms IS NULL OR typeof(released_at_ms)='integer')
);

CREATE TABLE ja_task_context_seed (
    context_seed_id TEXT CONSTRAINT pk_ja_task_context_seed_1 PRIMARY KEY NOT NULL,
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
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_task_context_seed_1 FOREIGN KEY (parent_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_context_seed_2 FOREIGN KEY (parent_turn_id) REFERENCES ja_conversation_turn(turn_id),
    CHECK ((inheritance_mode='EFFECTIVE_CONTEXT') = (effective_context_json IS NOT NULL)),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_task_mailbox_message (
    mailbox_sequence INTEGER CONSTRAINT pk_ja_task_mailbox_message_1 PRIMARY KEY AUTOINCREMENT,
    message_id TEXT CONSTRAINT uk_ja_task_mailbox_message_2 UNIQUE NOT NULL,
    root_thread_id TEXT NOT NULL,
    from_thread_id TEXT NOT NULL,
    sender_title TEXT NOT NULL,
    to_thread_id TEXT NOT NULL,
    causal_turn_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('MESSAGE','FOLLOW_UP','FINAL_ANSWER')),
    content_json TEXT NOT NULL CHECK (
        json_valid(content_json) AND json_type(content_json)='array'
    ),
    operation_key TEXT NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 128),
    state TEXT NOT NULL CHECK (state IN ('PENDING','BOUND','CONSUMED','CANCELLED')),
    bound_turn_id TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    consumed_at_ms INTEGER,
    CONSTRAINT fk_ja_task_mailbox_message_1 FOREIGN KEY (root_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_mailbox_message_2 FOREIGN KEY (to_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_mailbox_message_3 FOREIGN KEY (bound_turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT uk_ja_task_mailbox_message_1 UNIQUE (from_thread_id, operation_key),
    CHECK (kind='FOLLOW_UP' OR from_thread_id<>to_thread_id),
    CHECK ((state='BOUND') = (bound_turn_id IS NOT NULL AND consumed_at_ms IS NULL)),
    CHECK ((state='CONSUMED') = (consumed_at_ms IS NOT NULL)),
    CHECK (state!='PENDING' OR (bound_turn_id IS NULL AND consumed_at_ms IS NULL)),
    CHECK (state!='CANCELLED' OR consumed_at_ms IS NULL)
, CONSTRAINT uk_ja_task_mailbox_recipient UNIQUE(message_id,to_thread_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer'),
 CHECK(consumed_at_ms IS NULL OR typeof(consumed_at_ms)='integer')
);

CREATE TABLE ja_task_activity (
    activity_sequence INTEGER CONSTRAINT pk_ja_task_activity_1 PRIMARY KEY AUTOINCREMENT,
    activity_id TEXT CONSTRAINT uk_ja_task_activity_1 UNIQUE NOT NULL,
    root_thread_id TEXT NOT NULL,
    task_thread_id TEXT NOT NULL,
    actor_thread_id TEXT NOT NULL,
    causal_turn_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN (
        'CREATED','DISPATCHED','MESSAGE_SENT','FOLLOW_UP_QUEUED','PROGRESS','WAITING_APPROVAL','WAITING_INPUT',
        'RESUMED','COMPLETED','FAILED','CANCELLED','SUSPENDED'
    )),
    summary_json TEXT NOT NULL CHECK (
        json_valid(summary_json) AND json_type(summary_json)='object'
    ),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_task_activity_1 FOREIGN KEY (root_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_activity_2 FOREIGN KEY (task_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_activity_3 FOREIGN KEY (actor_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_activity_4 FOREIGN KEY (causal_turn_id) REFERENCES ja_conversation_turn(turn_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_task_projection (
    task_thread_id TEXT CONSTRAINT pk_ja_task_projection_1 PRIMARY KEY NOT NULL,
    root_thread_id TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    state TEXT NOT NULL CHECK (state IN (
        'IDLE','QUEUED','RUNNING','WAITING_APPROVAL','WAITING_INPUT','SUSPENDED','COMPLETED','FAILED','CANCELLED'
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
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_task_projection_1 FOREIGN KEY (task_thread_id) REFERENCES ja_conversation_lineage(child_thread_id),
    CONSTRAINT fk_ja_task_projection_2 FOREIGN KEY (root_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_task_projection_3 FOREIGN KEY (latest_activity_sequence) REFERENCES ja_task_activity(activity_sequence),
    CHECK (last_seen_activity_sequence IS NULL OR last_seen_activity_sequence<=latest_activity_sequence),
    CHECK ((state IN ('COMPLETED','FAILED','CANCELLED')) = (completed_at_ms IS NOT NULL)),
 CHECK(started_at_ms IS NULL OR typeof(started_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_task_process_generation (
    singleton_id INTEGER CONSTRAINT pk_ja_task_process_generation_1 PRIMARY KEY CHECK (singleton_id = 1),
    last_generation INTEGER NOT NULL CHECK (last_generation >= 0 AND last_generation < 9223372036854775807)
);

CREATE TABLE ja_tool_result_attachment (
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_tool_result_attachment PRIMARY KEY (thread_id,turn_id,call_id,ordinal),
    CONSTRAINT uk_ja_tool_result_attachment_identity UNIQUE (attachment_id),
    CONSTRAINT fk_ja_tool_result_attachment_call FOREIGN KEY (thread_id,turn_id,call_id)
        REFERENCES ja_tool_call(thread_id,turn_id,call_id),
    CONSTRAINT fk_ja_tool_result_attachment_attachment FOREIGN KEY (attachment_id)
        REFERENCES ja_attachment(attachment_id),
    CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_thread_fork_seed (
    thread_id TEXT CONSTRAINT pk_ja_thread_fork_seed PRIMARY KEY NOT NULL,
    source_thread_id TEXT NOT NULL,
    source_turn_id TEXT NOT NULL,
    source_item_id TEXT NOT NULL,
    source_thread_revision INTEGER NOT NULL CHECK (source_thread_revision >= 0),
    seed_json TEXT NOT NULL CHECK (json_valid(seed_json) AND json_type(seed_json)='object'),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_thread_fork_seed_thread FOREIGN KEY (thread_id)
        REFERENCES ja_conversation_thread(thread_id) ON DELETE CASCADE,
    CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_thread_fork_history_item (
    thread_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    item_id TEXT NOT NULL,
    source_thread_id TEXT NOT NULL,
    source_item_id TEXT NOT NULL,
    source_turn_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    context_through_ordinal INTEGER CHECK (context_through_ordinal IS NULL OR context_through_ordinal >= 1),
    item_json TEXT NOT NULL CHECK (json_valid(item_json) AND json_type(item_json)='object'),
    full_content TEXT,
    turn_json TEXT NOT NULL CHECK (json_valid(turn_json) AND json_type(turn_json)='object'),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_thread_fork_history_item PRIMARY KEY (thread_id,item_id),
    CONSTRAINT uk_ja_thread_fork_history_ordinal UNIQUE (thread_id,ordinal),
    CONSTRAINT fk_ja_thread_fork_history_thread FOREIGN KEY (thread_id)
        REFERENCES ja_conversation_thread(thread_id) ON DELETE CASCADE,
    CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_thread_fork_context_message (
    thread_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 1),
    source_thread_id TEXT NOT NULL,
    source_item_id TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    message_json TEXT NOT NULL CHECK (json_valid(message_json) AND json_type(message_json)='object'),
    CONSTRAINT pk_ja_thread_fork_context_message PRIMARY KEY (thread_id,ordinal),
    CONSTRAINT uk_ja_thread_fork_context_message_id UNIQUE (thread_id,source_message_id),
    CONSTRAINT fk_ja_thread_fork_context_thread FOREIGN KEY (thread_id)
        REFERENCES ja_conversation_thread(thread_id) ON DELETE CASCADE
);

CREATE INDEX idx_ja_thread_fork_history_page ON ja_thread_fork_history_item(thread_id,ordinal);

CREATE TABLE ja_thread_fork_operation (
    operation_key TEXT CONSTRAINT pk_ja_thread_fork_operation PRIMARY KEY NOT NULL CHECK (
        length(operation_key)=35 AND operation_key GLOB 'op_[0-9a-f]*'
        AND substr(operation_key,4) NOT GLOB '*[^0-9a-f]*'
    ),
    request_fingerprint TEXT NOT NULL CHECK (
        length(request_fingerprint)=64 AND request_fingerprint NOT GLOB '*[^0-9a-f]*'
    ),
    result_json TEXT NOT NULL CHECK (json_valid(result_json) AND json_type(result_json)='object'),
    created_at_ms INTEGER NOT NULL,
    CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_thread_fork_attachment (
    thread_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL,
    source_thread_id TEXT NOT NULL,
    source_attachment_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_thread_fork_attachment PRIMARY KEY (thread_id, attachment_id),
    CONSTRAINT uk_ja_thread_fork_attachment_ordinal UNIQUE (thread_id, ordinal),
    CONSTRAINT fk_ja_thread_fork_attachment_thread FOREIGN KEY (thread_id)
        REFERENCES ja_conversation_thread(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_ja_thread_fork_attachment_attachment FOREIGN KEY (attachment_id)
        REFERENCES ja_attachment(attachment_id),
    CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

-- The first runtime allocation must atomically advance this baseline row rather than create it during startup.
INSERT INTO ja_task_process_generation(singleton_id,last_generation) VALUES (1,0);

CREATE TABLE ja_plan (
    plan_id TEXT CONSTRAINT pk_ja_plan_1 PRIMARY KEY NOT NULL,
    owner_thread_id TEXT NOT NULL,
    objective TEXT NOT NULL CHECK (length(objective) BETWEEN 1 AND 32768),
    create_operation_key TEXT NOT NULL CHECK (length(create_operation_key) BETWEEN 8 AND 128),
    status TEXT NOT NULL CHECK (status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','PAUSED','COMPLETED','STOPPED')),
    revision INTEGER NOT NULL CHECK (revision>=0),
    active_plan_revision_id TEXT,
    active_run_id TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_1 FOREIGN KEY (owner_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_plan_2 FOREIGN KEY (active_plan_revision_id) REFERENCES ja_plan_revision(plan_revision_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_ja_plan_3 FOREIGN KEY (active_run_id) REFERENCES ja_plan_execution(run_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (status NOT IN ('APPROVED','EXECUTING','VERIFYING','PAUSED','COMPLETED') OR active_plan_revision_id IS NOT NULL),
    CHECK (status NOT IN ('EXECUTING','VERIFYING','PAUSED','COMPLETED') OR active_run_id IS NOT NULL),
    CHECK (status NOT IN ('DRAFT','AWAITING_APPROVAL','APPROVED') OR active_run_id IS NULL),
    CONSTRAINT uk_ja_plan_1 UNIQUE (owner_thread_id,create_operation_key),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_plan_draft (
    plan_draft_id TEXT CONSTRAINT pk_ja_plan_draft_1 PRIMARY KEY NOT NULL,
    plan_id TEXT CONSTRAINT uk_ja_plan_draft_1 UNIQUE NOT NULL,
    draft_revision INTEGER NOT NULL CHECK (draft_revision>=0),
    definition_json TEXT NOT NULL CHECK (json_valid(definition_json) AND json_type(definition_json)='object'),
    based_on_plan_revision_id TEXT,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_draft_1 FOREIGN KEY (plan_id) REFERENCES ja_plan(plan_id),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_plan_revision (
    plan_revision_id TEXT CONSTRAINT pk_ja_plan_revision_1 PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number>=1),
    definition_json TEXT NOT NULL CHECK (json_valid(definition_json) AND json_type(definition_json)='object'),
    plan_hash TEXT NOT NULL CHECK (length(plan_hash)=64 AND plan_hash NOT GLOB '*[^0-9a-f]*'),
    created_by TEXT NOT NULL CHECK (created_by IN ('AGENT','USER_UI')),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_revision_1 FOREIGN KEY (plan_id) REFERENCES ja_plan(plan_id),
    CONSTRAINT uk_ja_plan_revision_1 UNIQUE (plan_id,revision_number),
    CONSTRAINT uk_ja_plan_revision_2 UNIQUE (plan_id,plan_revision_id),
    CONSTRAINT uk_ja_plan_revision_3 UNIQUE (plan_id,plan_revision_id,plan_hash),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_plan_step (
    plan_revision_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>=0),
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 240),
    description TEXT NOT NULL,
    is_required INTEGER NOT NULL CHECK (is_required IN (0,1)),
    dependency_ids_json TEXT NOT NULL CHECK (json_valid(dependency_ids_json) AND json_type(dependency_ids_json)='array'),
    CONSTRAINT pk_ja_plan_step_1 PRIMARY KEY (plan_revision_id,step_id),
    CONSTRAINT uk_ja_plan_step_1 UNIQUE (plan_revision_id,ordinal),
    CONSTRAINT fk_ja_plan_step_1 FOREIGN KEY (plan_revision_id) REFERENCES ja_plan_revision(plan_revision_id)
);

CREATE TABLE ja_plan_acceptance_criterion (
    plan_revision_id TEXT NOT NULL,
    criterion_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>=0),
    description TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 2000),
    is_required INTEGER NOT NULL CHECK (is_required IN (0,1)),
    CONSTRAINT pk_ja_plan_acceptance_criterion_1 PRIMARY KEY (plan_revision_id,criterion_id),
    CONSTRAINT uk_ja_plan_acceptance_criterion_1 UNIQUE (plan_revision_id,ordinal),
    CONSTRAINT fk_ja_plan_acceptance_criterion_1 FOREIGN KEY (plan_revision_id) REFERENCES ja_plan_revision(plan_revision_id)
);

CREATE TABLE ja_plan_approval (
    approval_id TEXT CONSTRAINT pk_ja_plan_approval_1 PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    actor TEXT NOT NULL CHECK (actor='USER_UI'),
    decision TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_approval_1 FOREIGN KEY (plan_id,plan_revision_id,plan_hash) REFERENCES ja_plan_revision(plan_id,plan_revision_id,plan_hash),
    CONSTRAINT uk_ja_plan_approval_1 UNIQUE (plan_id,plan_revision_id,decision),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_plan_execution (
    run_id TEXT CONSTRAINT pk_ja_plan_execution_1 PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PREPARED','RUNNING','VERIFYING','PAUSED','COMPLETED','STOPPED')),
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    is_pause_requested INTEGER NOT NULL DEFAULT 0 CHECK (is_pause_requested IN (0,1)),
    used_model_rounds INTEGER NOT NULL DEFAULT 0 CHECK (used_model_rounds>=0),
    used_tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (used_tool_calls>=0),
    used_active_millis INTEGER NOT NULL DEFAULT 0 CHECK (used_active_millis>=0),
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_execution_1 FOREIGN KEY (plan_id,plan_revision_id,plan_hash) REFERENCES ja_plan_revision(plan_id,plan_revision_id,plan_hash),
    CHECK ((status IN ('COMPLETED','STOPPED'))=(completed_at_ms IS NOT NULL)),
 CHECK(started_at_ms IS NULL OR typeof(started_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer'),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_plan_turn_claim (
    run_id TEXT NOT NULL,
    turn_id TEXT NOT NULL CONSTRAINT uk_ja_plan_turn_claim_1 UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal>=1),
    claimed_at_ms INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'CLAIMED' CHECK (state IN ('CLAIMED','SETTLED','ABANDONED')),
    CONSTRAINT pk_ja_plan_turn_claim_1 PRIMARY KEY (run_id,ordinal),
    CONSTRAINT uk_ja_plan_turn_claim_owner UNIQUE (run_id,turn_id),
    CONSTRAINT fk_ja_plan_turn_claim_1 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
 CHECK(claimed_at_ms IS NULL OR typeof(claimed_at_ms)='integer')
);

CREATE TABLE "ja_plan_evaluation_attempt" (
    evaluation_id TEXT CONSTRAINT pk_ja_plan_evaluation_attempt_1 PRIMARY KEY NOT NULL,
    model_request_id TEXT CONSTRAINT uk_ja_plan_evaluation_attempt_2 UNIQUE NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    owner_thread_id TEXT NOT NULL,
    input_digest TEXT NOT NULL CHECK (length(input_digest)=64 AND input_digest NOT GLOB '*[^0-9a-f]*'),
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal>=1),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    outcome TEXT NOT NULL CHECK (outcome IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    certainty TEXT NOT NULL CHECK (certainty IN ('UNKNOWN','KNOWN')),
    verdict TEXT CHECK (verdict IS NULL OR verdict IN ('MET','NOT_MET','INCONCLUSIVE')),
    criteria_json TEXT CHECK (criteria_json IS NULL OR (json_valid(criteria_json) AND json_type(criteria_json)='array')),
    summary TEXT CHECK (summary IS NULL OR length(summary) BETWEEN 1 AND 4000),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens>=0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens>=0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens>=input_tokens+output_tokens),
    started_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER,
    CONSTRAINT fk_ja_plan_evaluation_attempt_1 FOREIGN KEY (plan_id) REFERENCES ja_plan(plan_id),
    CONSTRAINT fk_ja_plan_evaluation_attempt_2 FOREIGN KEY (plan_revision_id) REFERENCES ja_plan_revision(plan_revision_id),
    CONSTRAINT fk_ja_plan_evaluation_attempt_3 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
    CONSTRAINT fk_ja_plan_evaluation_attempt_4 FOREIGN KEY (owner_thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT uk_ja_plan_evaluation_attempt_1 UNIQUE (plan_id,plan_revision_id,run_id,input_digest,attempt_ordinal),
    CHECK ((certainty='UNKNOWN')=(input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)),
    CHECK ((certainty='KNOWN')=(input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL)),
    CHECK ((outcome='RUNNING')=(completed_at_ms IS NULL)),
    CHECK ((outcome='SUCCEEDED')=(verdict IS NOT NULL AND criteria_json IS NOT NULL AND summary IS NOT NULL)),
 CHECK(started_at_ms IS NULL OR typeof(started_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer')
);

CREATE TABLE ja_plan_step_execution (
    run_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING','READY','RUNNING','BLOCKED','SUCCEEDED','FAILED','SKIPPED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    failure_signature TEXT,
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT pk_ja_plan_step_execution_1 PRIMARY KEY (run_id,step_id),
    CONSTRAINT fk_ja_plan_step_execution_1 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
    CONSTRAINT fk_ja_plan_step_execution_2 FOREIGN KEY (plan_revision_id,step_id) REFERENCES ja_plan_step(plan_revision_id,step_id),
    CHECK (status!='RUNNING' OR (started_at_ms IS NOT NULL AND completed_at_ms IS NULL)),
    CHECK ((status IN ('SUCCEEDED','FAILED','SKIPPED'))=(completed_at_ms IS NOT NULL)),
 CHECK(started_at_ms IS NULL OR typeof(started_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_plan_acceptance_evidence (
    evidence_id TEXT CONSTRAINT pk_ja_plan_acceptance_evidence_1 PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    criterion_id TEXT,
    step_id TEXT,
    source_type TEXT NOT NULL CHECK (source_type IN ('TOOL_RESULT','TEST_REPORT','BUILD_ARTIFACT','REPOSITORY_STATE','UI_ASSERTION','USER_ACCEPTANCE')),
    source_id TEXT NOT NULL,
    summary TEXT NOT NULL CHECK (length(summary) BETWEEN 1 AND 4000),
    digest TEXT NOT NULL CHECK (length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
    observed_at_ms INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_acceptance_evidence_1 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
    CONSTRAINT fk_ja_plan_acceptance_evidence_2 FOREIGN KEY (plan_id,plan_revision_id) REFERENCES ja_plan_revision(plan_id,plan_revision_id),
 CHECK(observed_at_ms IS NULL OR typeof(observed_at_ms)='integer'),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_plan_tool_attempt (
    tool_attempt_id TEXT CONSTRAINT pk_ja_plan_tool_attempt_1 PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    step_id TEXT,
    attempt INTEGER NOT NULL CHECK (attempt>=1),
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL,
    process_generation INTEGER NOT NULL CHECK (process_generation>=1),
    side_effect INTEGER NOT NULL CHECK (side_effect IN (0,1)),
    state TEXT NOT NULL CHECK (state IN ('PREPARED','STARTED','SUCCEEDED','FAILED','CANCELLED','FAILED_BEFORE_START','CANCELLED_BEFORE_START','UNKNOWN')),
    request_digest TEXT NOT NULL CHECK (length(request_digest)=64 AND request_digest NOT GLOB '*[^0-9a-f]*'),
    result_digest TEXT CHECK (
        result_digest IS NULL OR (length(result_digest)=64 AND result_digest NOT GLOB '*[^0-9a-f]*')
    ),
    prepared_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    CONSTRAINT fk_ja_plan_tool_attempt_1 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
    CONSTRAINT fk_ja_plan_tool_attempt_2 FOREIGN KEY (turn_id,call_id) REFERENCES ja_tool_call(turn_id,call_id),
    CONSTRAINT fk_ja_plan_tool_attempt_3 FOREIGN KEY (plan_id,plan_revision_id) REFERENCES ja_plan_revision(plan_id,plan_revision_id),
    CONSTRAINT fk_ja_plan_tool_attempt_turn_claim FOREIGN KEY (run_id,turn_id) REFERENCES ja_plan_turn_claim(run_id,turn_id),
    CHECK ((state='PREPARED')=(started_at_ms IS NULL AND completed_at_ms IS NULL AND result_digest IS NULL)),
    CHECK (state!='STARTED' OR (started_at_ms IS NOT NULL AND completed_at_ms IS NULL AND result_digest IS NULL)),
    CHECK (state NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR
        (started_at_ms IS NOT NULL AND completed_at_ms IS NOT NULL AND result_digest IS NOT NULL)),
    CHECK (state NOT IN ('FAILED_BEFORE_START','CANCELLED_BEFORE_START') OR
        (started_at_ms IS NULL AND completed_at_ms IS NOT NULL AND result_digest IS NOT NULL)),
    CHECK (state!='UNKNOWN' OR (started_at_ms IS NOT NULL AND completed_at_ms IS NOT NULL)),
    CONSTRAINT uk_ja_plan_tool_attempt_1 UNIQUE (run_id,call_id,attempt),
 CHECK(prepared_at_ms IS NULL OR typeof(prepared_at_ms)='integer'),
 CHECK(started_at_ms IS NULL OR typeof(started_at_ms)='integer'),
 CHECK(completed_at_ms IS NULL OR typeof(completed_at_ms)='integer')
);

CREATE TABLE ja_plan_event (
    event_sequence INTEGER CONSTRAINT pk_ja_plan_event_1 PRIMARY KEY AUTOINCREMENT,
    event_id TEXT CONSTRAINT uk_ja_plan_event_2 UNIQUE NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision INTEGER NOT NULL CHECK (plan_revision>=0),
    activity TEXT NOT NULL,
    operation_key TEXT NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 128),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_plan_event_1 FOREIGN KEY (plan_id) REFERENCES ja_plan(plan_id),
    CONSTRAINT uk_ja_plan_event_1 UNIQUE (plan_id,operation_key),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_interaction_request (
    interaction_id TEXT CONSTRAINT pk_ja_interaction_request_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    tool_call_id TEXT NOT NULL,
    plan_revision_id TEXT,
    run_id TEXT,
    operation_key TEXT NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 256),
    questions_json TEXT NOT NULL CHECK (json_valid(questions_json) AND json_type(questions_json)='array'
        AND json_array_length(questions_json) BETWEEN 1 AND 3),
    answers_json TEXT NOT NULL CHECK (json_valid(answers_json) AND json_type(answers_json)='array'
        AND json_array_length(answers_json)<=3),
    status TEXT NOT NULL CHECK (status IN ('PENDING','ANSWERED','CANCELLED','SUPERSEDED')),
    revision INTEGER NOT NULL CHECK (revision>=0),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_interaction_request_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_interaction_request_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CONSTRAINT fk_ja_interaction_request_3 FOREIGN KEY (plan_revision_id) REFERENCES ja_plan_revision(plan_revision_id),
    CONSTRAINT fk_ja_interaction_request_4 FOREIGN KEY (run_id) REFERENCES ja_plan_execution(run_id),
    CONSTRAINT uk_ja_interaction_request_1 UNIQUE (thread_id,operation_key),
    CONSTRAINT uk_ja_interaction_request_2 UNIQUE (turn_id,tool_call_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer'),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_interaction_draft (
    interaction_id TEXT CONSTRAINT pk_ja_interaction_draft_1 PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    answers_json TEXT NOT NULL CHECK (json_valid(answers_json) AND json_type(answers_json)='array'
        AND json_array_length(answers_json)<=3),
    page INTEGER NOT NULL CHECK (page BETWEEN 0 AND 2),
    is_collapsed INTEGER NOT NULL CHECK (is_collapsed IN (0,1)),
    operation_key TEXT NOT NULL CHECK (length(operation_key) BETWEEN 1 AND 256),
    revision INTEGER NOT NULL CHECK (revision>=0),
    updated_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_interaction_draft_1 FOREIGN KEY (interaction_id) REFERENCES ja_interaction_request(interaction_id),
    CONSTRAINT fk_ja_interaction_draft_2 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
 CHECK(updated_at_ms IS NULL OR typeof(updated_at_ms)='integer')
);

CREATE TABLE ja_interaction_event (
    event_sequence INTEGER CONSTRAINT pk_ja_interaction_event_1 PRIMARY KEY AUTOINCREMENT,
    thread_id TEXT NOT NULL,
    interaction_id TEXT NOT NULL,
    interaction_revision INTEGER NOT NULL CHECK (interaction_revision>=0),
    kind TEXT NOT NULL CHECK (kind IN ('CREATED','DRAFT_CHANGED','ANSWERED','CANCELLED','SUPERSEDED')),
    occurred_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_interaction_event_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_interaction_event_2 FOREIGN KEY (interaction_id) REFERENCES ja_interaction_request(interaction_id),
 CHECK(occurred_at_ms IS NULL OR typeof(occurred_at_ms)='integer')
);

CREATE TABLE ja_client_operation (
    operation_key TEXT CONSTRAINT pk_ja_client_operation_1 PRIMARY KEY NOT NULL
        CHECK (length(operation_key)=35 AND substr(operation_key,1,3)='op_'
            AND substr(operation_key,4) NOT GLOB '*[^0-9a-f]*'),
    method TEXT NOT NULL CHECK (method IN ('turn/start','turn/continue','turn/reask','approval/respond')),
    request_fingerprint TEXT NOT NULL
        CHECK (length(request_fingerprint)=64 AND request_fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    thread_revision INTEGER NOT NULL CHECK (thread_revision>=0),
    is_queued INTEGER NOT NULL CHECK (is_queued IN (0,1)),
    approval_id TEXT,
    decision TEXT CHECK (decision IN ('approve','deny')),
    created_at_ms INTEGER NOT NULL,
    CONSTRAINT fk_ja_client_operation_1 FOREIGN KEY (thread_id) REFERENCES ja_conversation_thread(thread_id),
    CONSTRAINT fk_ja_client_operation_2 FOREIGN KEY (turn_id) REFERENCES ja_conversation_turn(turn_id),
    CHECK ((method='approval/respond')=(approval_id IS NOT NULL AND decision IS NOT NULL AND is_queued=0)),
    CHECK (method='approval/respond' OR is_queued=1),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_input_operation_receipt (
    operation_key TEXT CONSTRAINT pk_ja_input_operation_receipt_1 PRIMARY KEY NOT NULL
        CHECK (length(operation_key)=35 AND substr(operation_key,1,3)='op_'
            AND substr(operation_key,4) NOT GLOB '*[^0-9a-f]*'),
    request_fingerprint TEXT NOT NULL
        CHECK (length(request_fingerprint)=64 AND request_fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_id TEXT NOT NULL REFERENCES ja_conversation_thread(thread_id),
    turn_id TEXT NOT NULL REFERENCES ja_conversation_turn(turn_id),
    input_id TEXT NOT NULL,
    input_kind TEXT NOT NULL CHECK (input_kind IN ('STEERING','FOLLOW_UP')),
    created_at_ms INTEGER NOT NULL,
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_conversation_message (
 message_id TEXT CONSTRAINT pk_ja_conversation_message PRIMARY KEY NOT NULL,
 thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=1),
 role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT','TOOL')),
 kind TEXT NOT NULL CHECK(kind IN ('USER_INPUT','ASSISTANT','TOOL','REASONING_SUMMARY')),
 core_tenant_id TEXT NOT NULL, core_thread_id TEXT NOT NULL, core_turn_id TEXT NOT NULL, core_record_id TEXT NOT NULL,
 preview_text TEXT NOT NULL CHECK(length(preview_text)<=512),
 total_characters INTEGER NOT NULL CHECK(total_characters>=length(preview_text)),
 attachments_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(attachments_json) AND json_type(attachments_json)='array'),
 context_references_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(context_references_json) AND json_type(context_references_json)='array' AND json_array_length(context_references_json)<=64),
 created_at_ms INTEGER NOT NULL,
 CONSTRAINT uk_ja_conversation_message_ordinal UNIQUE(thread_id,ordinal),
 CONSTRAINT uk_ja_conversation_message_owner UNIQUE(message_id,thread_id,turn_id),
 CONSTRAINT uk_ja_conversation_message_core UNIQUE(core_tenant_id,core_thread_id,core_turn_id,core_record_id),
 CONSTRAINT fk_ja_conversation_message_turn FOREIGN KEY(turn_id,thread_id) REFERENCES ja_conversation_turn(turn_id,thread_id),
 CONSTRAINT fk_ja_conversation_message_core FOREIGN KEY(core_tenant_id,core_thread_id,core_turn_id,core_record_id) REFERENCES agent_record(tenant_id,thread_id,turn_id,record_id),
 CHECK(core_tenant_id='ja.local' AND core_thread_id=thread_id AND core_turn_id=turn_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_conversation_timeline_entry (
 item_id TEXT CONSTRAINT pk_ja_conversation_timeline_entry PRIMARY KEY NOT NULL,
 thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>=1),
 kind TEXT NOT NULL CHECK(kind IN ('USER_INPUT','ASSISTANT_PROGRESS','FINAL_ANSWER','REASONING_SUMMARY','THREAD_MESSAGE')),
 is_current_path INTEGER NOT NULL DEFAULT 1 CHECK(is_current_path IN (0,1)),
 model_round INTEGER CHECK(model_round IS NULL OR model_round>=1),
 message_id TEXT, mailbox_message_id TEXT,
 preview_text TEXT NOT NULL CHECK(length(preview_text)<=512),
 total_characters INTEGER NOT NULL CHECK(total_characters>=length(preview_text)),
 attachments_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(attachments_json) AND json_type(attachments_json)='array'),
 context_references_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(context_references_json) AND json_type(context_references_json)='array' AND json_array_length(context_references_json)<=64),
 source_thread_id TEXT, source_title TEXT, created_at_ms INTEGER NOT NULL,
 CONSTRAINT uk_ja_conversation_timeline_sequence UNIQUE(thread_id,sequence),
 CONSTRAINT fk_ja_conversation_timeline_turn FOREIGN KEY(turn_id,thread_id) REFERENCES ja_conversation_turn(turn_id,thread_id),
 CONSTRAINT fk_ja_conversation_timeline_message FOREIGN KEY(message_id,thread_id,turn_id) REFERENCES ja_conversation_message(message_id,thread_id,turn_id),
 CONSTRAINT fk_ja_conversation_timeline_mailbox FOREIGN KEY(mailbox_message_id,thread_id) REFERENCES ja_task_mailbox_message(message_id,to_thread_id),
 CHECK((message_id IS NULL)<>(mailbox_message_id IS NULL)),
 CHECK((kind='THREAD_MESSAGE')=(mailbox_message_id IS NOT NULL)),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);

CREATE TABLE ja_tool_call_preview (
 thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, assistant_message_record_id TEXT NOT NULL,
 source_event_id TEXT NOT NULL, source_event_sequence INTEGER NOT NULL CHECK(source_event_sequence>=1),
 -- Core model-batch ordinals are zero based and must retain their identity when the call is prepared.
 call_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0), tool_name TEXT NOT NULL,
 presentation_json TEXT NOT NULL CHECK(json_valid(presentation_json) AND json_type(presentation_json)='object'),
 created_at_ms INTEGER NOT NULL,
 CONSTRAINT pk_ja_tool_call_preview PRIMARY KEY(thread_id,turn_id,assistant_message_record_id,call_id),
 CONSTRAINT uk_ja_tool_call_preview_event UNIQUE(thread_id,turn_id,source_event_id,ordinal),
 CONSTRAINT fk_ja_tool_call_preview_turn FOREIGN KEY(turn_id,thread_id) REFERENCES ja_conversation_turn(turn_id,thread_id),
 CHECK(created_at_ms IS NULL OR typeof(created_at_ms)='integer')
);


CREATE UNIQUE INDEX uk_ja_conversation_lineage_subagent_name
    ON ja_conversation_lineage(parent_thread_id,task_name) WHERE task_kind='SUBAGENT';

CREATE UNIQUE INDEX uk_ja_interaction_request_pending_thread ON ja_interaction_request(thread_id) WHERE status='PENDING';

CREATE INDEX idx_ja_interaction_event_interaction_events_thread ON ja_interaction_event(thread_id,event_sequence);

CREATE TRIGGER interaction_request_owner_insert BEFORE INSERT ON ja_interaction_request
WHEN NOT EXISTS(SELECT 1 FROM ja_conversation_turn t WHERE t.turn_id=NEW.turn_id AND t.thread_id=NEW.thread_id)
 OR (NEW.run_id IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM ja_plan_execution r WHERE r.run_id=NEW.run_id
       AND r.plan_revision_id IS NEW.plan_revision_id))
BEGIN SELECT RAISE(ABORT,'Interaction owner is invalid'); END;

CREATE TRIGGER interaction_draft_owner_insert BEFORE INSERT ON ja_interaction_draft
WHEN NOT EXISTS(SELECT 1 FROM ja_interaction_request r
    WHERE r.interaction_id=NEW.interaction_id AND r.thread_id=NEW.thread_id)
BEGIN SELECT RAISE(ABORT,'Interaction draft owner is invalid'); END;

CREATE TRIGGER interaction_request_identity_update
BEFORE UPDATE OF interaction_id,thread_id,turn_id,tool_call_id,plan_revision_id,run_id,questions_json,created_at_ms
ON ja_interaction_request
BEGIN SELECT RAISE(ABORT,'Interaction identity is immutable'); END;

CREATE UNIQUE INDEX uk_ja_plan_turn_claim_live_run ON ja_plan_turn_claim(run_id) WHERE state='CLAIMED';

CREATE INDEX idx_ja_conversation_thread_threads_workspace_updated ON ja_conversation_thread(workspace_id, updated_at_ms DESC, thread_id DESC);

CREATE INDEX idx_ja_attachment_attachments_draft_expiry
    ON ja_attachment(status,expires_at_ms,attachment_id) WHERE status='DRAFT';

CREATE INDEX idx_ja_attachment_attachments_blob_active
    ON ja_attachment(blob_sha256,status) WHERE status IN ('DRAFT','BOUND');

CREATE INDEX idx_ja_approval_approvals_pending ON ja_approval(thread_id,expires_at_ms) WHERE decision IS NULL;

CREATE INDEX idx_ja_tool_call_tools_recovery ON ja_tool_call(state,thread_id,turn_id);
CREATE INDEX idx_ja_tool_result_attachment_thread ON ja_tool_result_attachment(thread_id,turn_id,call_id);

CREATE INDEX idx_ja_conversation_thread_threads_workspace_active_pinned
    ON ja_conversation_thread(workspace_id, pinned_at_ms DESC, updated_at_ms DESC, thread_id DESC)
    WHERE archived_at_ms IS NULL AND deleted_at_ms IS NULL;

CREATE INDEX idx_ja_conversation_lineage_thread_lineage_root ON ja_conversation_lineage(root_thread_id, depth, created_at_ms, child_thread_id);

CREATE INDEX idx_ja_conversation_lineage_thread_lineage_parent ON ja_conversation_lineage(parent_thread_id, created_at_ms, child_thread_id);

CREATE INDEX idx_ja_task_mailbox_message_task_mailbox_target_pending
    ON ja_task_mailbox_message(to_thread_id, state, mailbox_sequence);

CREATE INDEX idx_ja_task_mailbox_message_task_mailbox_root_sequence
    ON ja_task_mailbox_message(root_thread_id, mailbox_sequence);

CREATE INDEX idx_ja_task_activity_task_activities_root_sequence
    ON ja_task_activity(root_thread_id, activity_sequence);

CREATE INDEX idx_ja_task_activity_task_activities_task_sequence
    ON ja_task_activity(task_thread_id, activity_sequence);

CREATE INDEX idx_ja_task_projection_task_projections_root_state
    ON ja_task_projection(root_thread_id, state, updated_at_ms DESC, task_thread_id);

CREATE UNIQUE INDEX uk_ja_workspace_write_claim_held_workspace
    ON ja_workspace_write_claim(workspace_id) WHERE state='HELD';

CREATE INDEX idx_ja_workspace_write_claim_workspace_write_claim_fifo
    ON ja_workspace_write_claim(workspace_id, state, claim_sequence);

CREATE UNIQUE INDEX uk_ja_turn_input_turn_priority
    ON ja_turn_input(turn_id,priority_sequence) WHERE priority_sequence IS NOT NULL;

CREATE INDEX idx_ja_turn_input_pending_inputs_queue
    ON ja_turn_input(turn_id,state,kind,priority_sequence,input_sequence);

CREATE INDEX idx_ja_turn_input_attachment_pending_input_attachments_attachment
    ON ja_turn_input_attachment(attachment_id, input_id);

CREATE INDEX idx_ja_conversation_turn_turns_thread_sequence ON ja_conversation_turn(thread_id,turn_sequence);

CREATE UNIQUE INDEX uk_ja_conversation_turn_active_thread ON ja_conversation_turn(thread_id)
    WHERE state IN ('RUNNING','WAITING_APPROVAL','WAITING_INPUT');

CREATE INDEX idx_ja_conversation_turn_turns_parent_turn ON ja_conversation_turn(parent_turn_id,turn_sequence);

CREATE INDEX idx_ja_conversation_turn_turns_root_turn ON ja_conversation_turn(root_turn_id,turn_sequence);

CREATE INDEX idx_ja_tool_binding_tool_bindings_batch ON ja_tool_binding(turn_id,batch_id,call_id);

CREATE INDEX idx_ja_plan_plans_owner ON ja_plan(owner_thread_id,updated_at_ms,plan_id);

CREATE UNIQUE INDEX uk_ja_plan_nonterminal_owner ON ja_plan(owner_thread_id)
    WHERE status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','PAUSED');

CREATE UNIQUE INDEX uk_ja_plan_execution_live_plan ON ja_plan_execution(plan_id) WHERE status IN ('PREPARED','RUNNING','VERIFYING','PAUSED');

CREATE UNIQUE INDEX uk_ja_plan_acceptance_evidence_source ON ja_plan_acceptance_evidence(
  run_id,source_type,source_id,digest,COALESCE(criterion_id,''),COALESCE(step_id,''));

CREATE INDEX idx_ja_plan_tool_attempt_plan_tool_attempts_recovery ON ja_plan_tool_attempt(state,process_generation,side_effect,prepared_at_ms,tool_attempt_id);

CREATE TRIGGER context_checkpoints_immutable_update BEFORE UPDATE ON ja_context_checkpoint
BEGIN SELECT RAISE(ABORT, 'context checkpoints are immutable'); END;

CREATE TRIGGER task_context_seeds_immutable_update BEFORE UPDATE ON ja_task_context_seed
BEGIN SELECT RAISE(ABORT,'task context seeds are immutable'); END;

CREATE TRIGGER thread_lineage_immutable_update BEFORE UPDATE ON ja_conversation_lineage
BEGIN SELECT RAISE(ABORT,'thread lineage is immutable'); END;

CREATE TRIGGER task_activities_immutable_update BEFORE UPDATE ON ja_task_activity
BEGIN SELECT RAISE(ABORT,'task activities are append only'); END;

CREATE TRIGGER tool_bindings_immutable_update BEFORE UPDATE ON ja_tool_binding
BEGIN SELECT RAISE(ABORT,'Tool bindings are immutable'); END;

CREATE TRIGGER turn_internal_context_immutable BEFORE UPDATE ON ja_turn_internal_context
BEGIN SELECT RAISE(ABORT,'Turn internal context is immutable'); END;

CREATE TRIGGER plan_revisions_immutable_update BEFORE UPDATE ON ja_plan_revision BEGIN SELECT RAISE(ABORT,'plan revisions are immutable'); END;

CREATE TRIGGER plan_steps_immutable_update BEFORE UPDATE ON ja_plan_step BEGIN SELECT RAISE(ABORT,'plan steps are immutable'); END;

CREATE TRIGGER acceptance_criteria_immutable_update BEFORE UPDATE ON ja_plan_acceptance_criterion BEGIN SELECT RAISE(ABORT,'acceptance criteria are immutable'); END;

CREATE TRIGGER plan_approvals_immutable_update BEFORE UPDATE ON ja_plan_approval BEGIN SELECT RAISE(ABORT,'plan ja_approval are immutable'); END;

CREATE TRIGGER acceptance_evidence_immutable_update BEFORE UPDATE ON ja_plan_acceptance_evidence BEGIN SELECT RAISE(ABORT,'acceptance evidence is append only'); END;

CREATE TRIGGER plan_events_immutable_update BEFORE UPDATE ON ja_plan_event BEGIN SELECT RAISE(ABORT,'plan events are append only'); END;

CREATE TRIGGER plan_step_executions_run_revision_insert BEFORE INSERT ON ja_plan_step_execution
WHEN NOT EXISTS(
  SELECT 1 FROM ja_plan_execution r
  WHERE r.run_id=NEW.run_id AND r.plan_revision_id=NEW.plan_revision_id
)
BEGIN SELECT RAISE(ABORT,'Plan step execution run revision is invalid'); END;

CREATE TRIGGER plan_step_executions_identity_update
BEFORE UPDATE OF run_id,plan_revision_id,step_id ON ja_plan_step_execution
BEGIN SELECT RAISE(ABORT,'Plan step execution identity is immutable'); END;

CREATE TRIGGER pending_inputs_turn_owner_insert BEFORE INSERT ON ja_turn_input
WHEN NOT EXISTS(
  SELECT 1 FROM ja_conversation_turn t WHERE t.turn_id=NEW.turn_id AND t.thread_id=NEW.thread_id
)
BEGIN SELECT RAISE(ABORT,'Pending input Turn owner is invalid'); END;

CREATE TRIGGER pending_inputs_owner_update
BEFORE UPDATE OF thread_id,turn_id ON ja_turn_input
BEGIN SELECT RAISE(ABORT,'Pending input owner is immutable'); END;

CREATE TRIGGER plan_turn_claim_admission_insert BEFORE INSERT ON ja_conversation_turn
WHEN EXISTS(SELECT 1 FROM ja_plan_turn_claim WHERE turn_id=NEW.turn_id)
 AND NOT EXISTS(
  SELECT 1 FROM ja_plan_turn_claim c
  JOIN ja_plan_execution r ON r.run_id=c.run_id
  JOIN ja_plan p ON p.plan_id=r.plan_id
  WHERE c.turn_id=NEW.turn_id AND c.state='CLAIMED'
    AND r.status='RUNNING' AND r.is_pause_requested=0
    AND p.status='EXECUTING' AND p.active_run_id=r.run_id
    AND p.active_plan_revision_id=r.plan_revision_id
    AND p.owner_thread_id=NEW.thread_id
 )
BEGIN SELECT RAISE(ABORT,'Plan Turn admission is fenced'); END;

CREATE TRIGGER plan_tool_attempts_run_owner_insert BEFORE INSERT ON ja_plan_tool_attempt
WHEN NOT EXISTS(
  SELECT 1 FROM ja_plan_execution r
  JOIN ja_plan p ON p.plan_id=r.plan_id
  JOIN ja_plan_turn_claim c ON c.run_id=r.run_id AND c.turn_id=NEW.turn_id AND c.state='CLAIMED'
  JOIN ja_conversation_turn t ON t.turn_id=c.turn_id AND t.thread_id=p.owner_thread_id
  WHERE r.run_id=NEW.run_id
    AND r.plan_id=NEW.plan_id
    AND r.plan_revision_id=NEW.plan_revision_id
)
BEGIN SELECT RAISE(ABORT,'Plan Tool attempt run owner is invalid'); END;

CREATE TRIGGER plan_tool_attempts_identity_update
BEFORE UPDATE OF tool_attempt_id,plan_id,run_id,plan_revision_id,
  step_id,attempt,turn_id,call_id,process_generation,side_effect,request_digest,prepared_at_ms
ON ja_plan_tool_attempt
BEGIN SELECT RAISE(ABORT,'Plan Tool attempt identity is immutable'); END;

CREATE TRIGGER acceptance_evidence_criterion_owner_insert BEFORE INSERT ON ja_plan_acceptance_evidence
WHEN NEW.criterion_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ja_plan_acceptance_criterion p
  WHERE p.plan_revision_id=NEW.plan_revision_id
    AND p.criterion_id=NEW.criterion_id
)
BEGIN SELECT RAISE(ABORT,'acceptance evidence criterion owner is invalid'); END;

CREATE TRIGGER plans_active_identity_insert BEFORE INSERT ON ja_plan
WHEN (NEW.active_plan_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ja_plan_revision r WHERE r.plan_revision_id=NEW.active_plan_revision_id AND r.plan_id=NEW.plan_id
)) OR (NEW.active_run_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ja_plan_execution x WHERE x.run_id=NEW.active_run_id AND x.plan_id=NEW.plan_id
))
BEGIN SELECT RAISE(ABORT,'Plan active identity owner is invalid'); END;

CREATE TRIGGER plans_active_identity_update BEFORE UPDATE OF active_plan_revision_id,active_run_id ON ja_plan
WHEN (NEW.active_plan_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ja_plan_revision r WHERE r.plan_revision_id=NEW.active_plan_revision_id AND r.plan_id=NEW.plan_id
)) OR (NEW.active_run_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ja_plan_execution x WHERE x.run_id=NEW.active_run_id AND x.plan_id=NEW.plan_id
))
BEGIN SELECT RAISE(ABORT,'Plan active identity owner is invalid'); END;

CREATE INDEX idx_ja_tool_recovery_ix_tool_recoveries_turn_pending ON ja_tool_recovery(turn_id, state, recovery_revision);

CREATE INDEX idx_ja_conversation_turn_turns_thread_current_path ON ja_conversation_turn(thread_id,is_current_path,turn_sequence);

CREATE INDEX idx_ja_conversation_turn_turns_source_current_path
    ON ja_conversation_turn(thread_id,source_message_id,is_current_path,turn_sequence)
    WHERE source_message_id IS NOT NULL;

CREATE INDEX idx_ja_message_attachment_message_attachments_attachment ON ja_message_attachment(attachment_id, message_id);

CREATE INDEX idx_ja_client_operation_client_operations_turn ON ja_client_operation(thread_id,turn_id);

CREATE TRIGGER usage_unknown_only_insert BEFORE INSERT ON ja_turn_usage
WHEN NEW.certainty<>'UNKNOWN'
BEGIN SELECT RAISE(ABORT,'ja_turn_usage request must start as UNKNOWN'); END;

CREATE TRIGGER usage_settlement_only_update BEFORE UPDATE ON ja_turn_usage
WHEN NOT (
    OLD.certainty='UNKNOWN'
    AND NEW.certainty='KNOWN'
    AND NEW.usage_id=OLD.usage_id AND NEW.model_request_id=OLD.model_request_id
    AND NEW.thread_id=OLD.thread_id AND NEW.turn_id=OLD.turn_id
    AND NEW.model_round=OLD.model_round AND NEW.model_request_ordinal=OLD.model_request_ordinal
    AND NEW.purpose=OLD.purpose
    AND NEW.profile_json=OLD.profile_json AND NEW.created_at_ms>=OLD.created_at_ms
    AND NEW.input_tokens IS NOT NULL AND NEW.output_tokens IS NOT NULL
    AND NEW.total_tokens>=NEW.input_tokens+NEW.output_tokens
)
BEGIN SELECT RAISE(ABORT,'ja_turn_usage update is not a request settlement'); END;

CREATE INDEX idx_ja_plan_evaluation_attempt_plan_evaluation_requests_run ON ja_plan_evaluation_attempt(plan_id,run_id,outcome);

CREATE INDEX idx_ja_plan_evaluation_attempt_plan_evaluation_requests_input ON ja_plan_evaluation_attempt(
    plan_id,plan_revision_id,run_id,input_digest,attempt_ordinal DESC);

CREATE TRIGGER plan_evaluation_requests_immutable_identity
BEFORE UPDATE OF evaluation_id,model_request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,
    attempt_ordinal,profile_json,started_at_ms ON ja_plan_evaluation_attempt
BEGIN SELECT RAISE(ABORT,'Plan evaluator request identity is immutable'); END;

CREATE INDEX idx_ja_conversation_timeline_page ON ja_conversation_timeline_entry(thread_id,is_current_path,created_at_ms,item_id);
CREATE INDEX idx_ja_conversation_timeline_input_history ON ja_conversation_timeline_entry(created_at_ms DESC,item_id DESC) WHERE kind='USER_INPUT';
CREATE INDEX idx_ja_conversation_message_core ON ja_conversation_message(core_tenant_id,core_thread_id,core_turn_id,core_record_id);

-- 进程启动事实先于外部派发保存；新进程代际只标记中断，不恢复或重放命令副作用。
CREATE TABLE ja_process_session (
    session_id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    started_turn_id TEXT NOT NULL,
    started_call_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK (generation >= 1),
    command TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('starting','running','stopping','exited','failed','interrupted')),
    output_sequence INTEGER NOT NULL DEFAULT 0 CHECK (output_sequence >= 0),
    output_text TEXT NOT NULL DEFAULT '',
    exit_code INTEGER,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    CONSTRAINT fk_ja_process_session_thread FOREIGN KEY (thread_id)
        REFERENCES ja_conversation_thread(thread_id) ON DELETE CASCADE,
    CONSTRAINT uq_ja_process_session_call UNIQUE (thread_id, started_turn_id, started_call_id)
);
CREATE INDEX ix_ja_process_session_thread_started
    ON ja_process_session(workspace_id, thread_id, started_at DESC);

-- 输入投递先记录意图；重复请求不得重放未知副作用。
CREATE TABLE ja_process_write_operation (
    session_id TEXT NOT NULL,
    operation_key TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('claimed', 'applied', 'unknown')),
    created_at TEXT NOT NULL,
    completed_at TEXT,
    PRIMARY KEY (session_id, operation_key),
    FOREIGN KEY (session_id) REFERENCES ja_process_session(session_id) ON DELETE CASCADE
);
