-- @author kongweiguang
-- SPDX-License-Identifier: GPL-3.0-or-later

-- 原始 usage 列保持不变；新增列只保存 Provider 已明确报告或可从其明确口径规范化出的数值。
ALTER TABLE usage ADD COLUMN cache_read_tokens INTEGER CHECK (cache_read_tokens IS NULL OR cache_read_tokens >= 0);
ALTER TABLE usage ADD COLUMN cache_write_tokens INTEGER CHECK (cache_write_tokens IS NULL OR cache_write_tokens >= 0);
ALTER TABLE usage ADD COLUMN new_input_tokens INTEGER CHECK (new_input_tokens IS NULL OR new_input_tokens >= 0);
ALTER TABLE usage ADD COLUMN input_accounting TEXT NOT NULL DEFAULT 'UNKNOWN'
  CHECK (input_accounting IN ('INPUT_EXCLUDES_CACHE','INPUT_INCLUDES_CACHE','UNKNOWN'));

-- 工具启动与回执之间崩溃时，原 tools 行仍只表达执行生命周期；本表单独保存“结果未知”的
-- 恢复证据和用户裁决，避免把当前文件满足条件误写成原调用已经成功。
CREATE TABLE tool_recoveries (
    recovery_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    call_id TEXT NOT NULL UNIQUE,
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
    idempotency_key TEXT UNIQUE,
    verified_at TEXT,
    resolved_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE CASCADE,
    FOREIGN KEY (call_id) REFERENCES tools(call_id) ON DELETE CASCADE,
    CHECK ((evidence_kind='NONE') = (
        target_relative_path IS NULL AND expected_after_sha256 IS NULL AND expected_after_bytes IS NULL
    )),
    CHECK ((evidence_kind='FILE_TEXT') = (
        target_relative_path IS NOT NULL AND expected_after_sha256 IS NOT NULL AND expected_after_bytes IS NOT NULL
    )),
    CHECK ((state='VERIFIED') = (verified_at IS NOT NULL AND resolved_at IS NOT NULL)),
    CHECK ((state IN ('RETRIED','SKIPPED')) = (resolved_at IS NOT NULL))
);

CREATE TABLE tool_recovery_attempts (
    recovery_attempt_id TEXT PRIMARY KEY NOT NULL,
    recovery_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    disposition TEXT NOT NULL CHECK (disposition IN ('STARTUP','VERIFY','RETRY','SKIP')),
    idempotency_key TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (recovery_id) REFERENCES tool_recoveries(recovery_id) ON DELETE CASCADE,
    UNIQUE (recovery_id, attempt_number),
    UNIQUE (recovery_id, idempotency_key)
);

-- 投影阶段只保存每个 Tool 结果已选定的表现形式，原始消息和 Tool 正文继续留在既有历史表。
CREATE TABLE context_projection_stages (
    stage_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    stage_number INTEGER NOT NULL CHECK (stage_number >= 1),
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    reason TEXT NOT NULL CHECK (reason IN ('INITIAL','CHECKPOINT','MODEL_BINDING','TOOL_BINDING','OVERFLOW')),
    model_binding TEXT NOT NULL CHECK (
        length(model_binding)=64 AND model_binding NOT GLOB '*[^0-9a-f]*'
    ),
    tool_binding TEXT NOT NULL CHECK (
        length(tool_binding)=64 AND tool_binding NOT GLOB '*[^0-9a-f]*'
    ),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE,
    UNIQUE (thread_id, stage_number),
    -- 同一 source/binding/reason 的恢复重试必须收敛到同一个阶段；阶段号仍保留为展示和顺序锚点。
    UNIQUE (thread_id, source_revision, reason, model_binding, tool_binding)
);

CREATE TABLE context_projection_entries (
    stage_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    projection TEXT NOT NULL CHECK (projection IN ('FULL','HEAD_TAIL','ARTIFACT')),
    created_at TEXT NOT NULL,
    PRIMARY KEY (stage_id, message_id),
    FOREIGN KEY (stage_id) REFERENCES context_projection_stages(stage_id) ON DELETE CASCADE
);

CREATE INDEX ix_tool_recoveries_turn_pending ON tool_recoveries(turn_id, state, recovery_revision);
CREATE INDEX ix_context_projection_stages_thread ON context_projection_stages(thread_id, stage_number DESC);
