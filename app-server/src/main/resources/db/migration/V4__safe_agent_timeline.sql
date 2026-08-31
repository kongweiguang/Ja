-- @author kongweiguang

-- UI 文本与 Provider 上下文物理分离；message_kind 是当前唯一公开阶段词汇。
CREATE TABLE timeline_messages (
    item_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    message_kind TEXT NOT NULL CHECK (message_kind IN (
        'USER_INPUT','ASSISTANT_PROGRESS','REASONING_SUMMARY','FINAL_ANSWER'
    )),
    public_text TEXT NOT NULL,
    model_round INTEGER CHECK (model_round BETWEEN 1 AND 128),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((message_kind IN ('ASSISTANT_PROGRESS','REASONING_SUMMARY')) = (model_round IS NOT NULL))
);

CREATE INDEX idx_timeline_messages_thread_created
    ON timeline_messages(thread_id, created_at, item_id);

-- 旧可见消息一次性迁移；成功 Turn 的最后一条 assistant 才能成为 final，其余一律 progress。
INSERT INTO timeline_messages(item_id,thread_id,turn_id,message_kind,public_text,model_round,created_at)
SELECT m.message_id,m.thread_id,m.turn_id,
       CASE WHEN m.role='USER' THEN 'USER_INPUT'
            WHEN t.state='COMPLETED' AND m.ordinal=(
                SELECT MAX(last.ordinal) FROM messages last
                WHERE last.turn_id=m.turn_id AND last.role='ASSISTANT'
            ) THEN 'FINAL_ANSWER' ELSE 'ASSISTANT_PROGRESS' END,
       COALESCE((SELECT group_concat(json_extract(block.value,'$.text'),'')
                 FROM json_each(m.blocks_json) block
                 WHERE json_extract(block.value,'$.kind')='text'),''),
       CASE WHEN m.role='ASSISTANT' AND NOT (t.state='COMPLETED' AND m.ordinal=(
                SELECT MAX(last.ordinal) FROM messages last
                WHERE last.turn_id=m.turn_id AND last.role='ASSISTANT'
            )) THEN COALESCE((SELECT MAX(u.model_round) FROM usage u WHERE u.turn_id=m.turn_id),1)
            ELSE NULL END,
       m.created_at
FROM messages m JOIN turns t ON t.turn_id=m.turn_id
WHERE m.role IN ('USER','ASSISTANT')
  AND EXISTS(SELECT 1 FROM json_each(m.blocks_json) block WHERE json_extract(block.value,'$.kind')='text');

-- tools 不再保存 raw arguments/result；模型续传仍由不可公开的 messages.blocks_json 保持严格配对。
-- 先暂存审批并移除旧索引，避免 tools rename 把审批外键永久改指 legacy 表。
ALTER TABLE approvals RENAME TO approvals_legacy_v4;
DROP INDEX idx_approvals_pending;
ALTER TABLE tools RENAME TO tools_legacy_v4;
CREATE TABLE tools (
    call_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    tool_name TEXT NOT NULL,
    side_effect TEXT NOT NULL CHECK (side_effect IN ('READ_ONLY','EXTERNAL')),
    presentation_json TEXT NOT NULL CHECK (json_valid(presentation_json) AND json_type(presentation_json)='object'),
    artifact_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('PREPARED','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id,ordinal), UNIQUE (turn_id,call_id)
);

INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,state,revision,created_at,updated_at)
SELECT call_id,thread_id,turn_id,ordinal,tool_name,side_effect,
       json_object('kind',CASE WHEN tool_name IN ('read','read_attachment') THEN 'read'
                               WHEN tool_name IN ('edit','write','shell') THEN tool_name ELSE 'mcp' END,
                   'title',tool_name,'status',CASE state WHEN 'PREPARED' THEN 'pending' WHEN 'RUNNING' THEN 'running'
                       WHEN 'SUCCEEDED' THEN 'success' WHEN 'FAILED' THEN 'error'
                       WHEN 'CANCELLED' THEN 'cancelled' ELSE 'unknown' END,
                   'relativePaths',json_array(),'truncated',json('false')),
       state,revision,created_at,updated_at
FROM tools_legacy_v4;

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

INSERT INTO approvals(approval_id,thread_id,turn_id,call_id,decision,requested_at,resolved_at,expires_at)
SELECT approval_id,thread_id,turn_id,call_id,decision,requested_at,resolved_at,expires_at
FROM approvals_legacy_v4;

DROP TABLE approvals_legacy_v4;
DROP TABLE tools_legacy_v4;
CREATE INDEX idx_tools_recovery ON tools(state,thread_id,turn_id);
CREATE INDEX idx_approvals_pending ON approvals(thread_id,expires_at) WHERE decision IS NULL;

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
