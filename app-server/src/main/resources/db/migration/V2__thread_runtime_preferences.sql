-- @author kongweiguang
-- v2 删除旧 Profile selector；历史行保持真实 NULL，新写入由触发器要求完整 v3 偏好与运行快照。

DROP INDEX idx_threads_workspace_profile;
ALTER TABLE threads ADD COLUMN provider_id TEXT;
ALTER TABLE threads ADD COLUMN model_id TEXT;
ALTER TABLE threads ADD COLUMN reasoning_effort TEXT CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('low', 'medium', 'high'));
ALTER TABLE threads ADD COLUMN access_mode TEXT CHECK (access_mode IS NULL OR access_mode IN ('APPROVAL_REQUIRED', 'FULL_ACCESS'));
ALTER TABLE threads ADD COLUMN title_source TEXT CHECK (title_source IS NULL OR title_source IN ('PLACEHOLDER', 'USER', 'AUTOMATIC'));
ALTER TABLE threads DROP COLUMN profile_id;

CREATE INDEX idx_threads_workspace_updated_v2
    ON threads(workspace_id, updated_at DESC, thread_id DESC);

CREATE TRIGGER threads_v3_preferences_required_insert
BEFORE INSERT ON threads
WHEN NEW.provider_id IS NULL OR NEW.model_id IS NULL OR NEW.access_mode IS NULL OR NEW.title_source IS NULL
BEGIN SELECT RAISE(ABORT, 'thread preferences are required'); END;

DROP INDEX idx_turns_generation;
ALTER TABLE turns ADD COLUMN provider_id TEXT;
ALTER TABLE turns ADD COLUMN model_id TEXT;
ALTER TABLE turns ADD COLUMN provider TEXT CHECK (provider IS NULL OR provider IN ('openai', 'anthropic'));
ALTER TABLE turns ADD COLUMN api TEXT CHECK (api IS NULL OR api IN ('openai_responses', 'openai_chat_completions', 'anthropic_messages'));
ALTER TABLE turns ADD COLUMN upstream_model TEXT;
ALTER TABLE turns ADD COLUMN reasoning_effort TEXT CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('low', 'medium', 'high'));
ALTER TABLE turns ADD COLUMN access_mode TEXT CHECK (access_mode IS NULL OR access_mode IN ('APPROVAL_REQUIRED', 'FULL_ACCESS'));
ALTER TABLE turns DROP COLUMN profile_id;

CREATE INDEX idx_turns_generation_v2
    ON turns(config_generation, provider_id, model_id, requested_at, turn_id);

CREATE TRIGGER turns_v3_runtime_required_insert
BEFORE INSERT ON turns
WHEN NEW.provider_id IS NULL OR NEW.model_id IS NULL OR NEW.provider IS NULL OR NEW.api IS NULL
  OR NEW.upstream_model IS NULL OR NEW.access_mode IS NULL OR NEW.config_generation IS NULL
BEGIN SELECT RAISE(ABORT, 'turn runtime snapshot is required'); END;
