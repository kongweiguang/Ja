-- @author kongweiguang
-- v3 收紧 Provider API，并新增受管附件与独立自动标题用量事实；Flyway 事务保证失败不留下半迁移。

-- 先复制到严格列；任一存量旧 API 会触发 CHECK 并使整个 migration 回滚，绝不重写历史事实。
DROP TRIGGER turns_v3_runtime_required_insert;
ALTER TABLE turns ADD COLUMN api_v3 TEXT
    CHECK (api_v3 IS NULL OR api_v3 IN ('openai_responses', 'anthropic_messages'));
UPDATE turns SET api_v3=api;
ALTER TABLE turns DROP COLUMN api;
ALTER TABLE turns RENAME COLUMN api_v3 TO api;

CREATE TRIGGER turns_v3_runtime_required_insert
BEFORE INSERT ON turns
WHEN NEW.provider_id IS NULL OR NEW.model_id IS NULL OR NEW.provider IS NULL OR NEW.api IS NULL
  OR NEW.upstream_model IS NULL OR NEW.access_mode IS NULL OR NEW.config_generation IS NULL
BEGIN SELECT RAISE(ABORT, 'turn runtime snapshot is required'); END;

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

CREATE INDEX idx_attachments_draft_expiry
    ON attachments(status,expires_at,attachment_id) WHERE status='DRAFT';
CREATE INDEX idx_attachments_blob_active
    ON attachments(blob_sha256,status) WHERE status IN ('DRAFT','BOUND');

CREATE TABLE turn_attachments (
    turn_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL UNIQUE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at TEXT NOT NULL,
    PRIMARY KEY (turn_id,ordinal),
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY (attachment_id) REFERENCES attachments(attachment_id) ON DELETE RESTRICT
);

CREATE INDEX idx_turn_attachments_attachment ON turn_attachments(attachment_id,turn_id);

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
