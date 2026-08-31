-- @author kongweiguang
-- 七档逻辑思考级别替换旧三档 effort；先复制历史事实，再删除旧列和带代际名称的触发器。

DROP TRIGGER threads_v3_preferences_required_insert;
ALTER TABLE threads ADD COLUMN reasoning_level TEXT CHECK (
    reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
);
UPDATE threads SET reasoning_level=reasoning_effort;
ALTER TABLE threads DROP COLUMN reasoning_effort;

CREATE TRIGGER threads_preferences_required_insert
BEFORE INSERT ON threads
WHEN NEW.provider_id IS NULL OR NEW.model_id IS NULL OR NEW.access_mode IS NULL OR NEW.title_source IS NULL
BEGIN SELECT RAISE(ABORT, 'thread preferences are required'); END;

DROP TRIGGER turns_v3_runtime_required_insert;
ALTER TABLE turns ADD COLUMN reasoning_level TEXT CHECK (
    reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
);
UPDATE turns SET reasoning_level=reasoning_effort;
ALTER TABLE turns DROP COLUMN reasoning_effort;

CREATE TRIGGER turns_runtime_required_insert
BEFORE INSERT ON turns
WHEN NEW.provider_id IS NULL OR NEW.model_id IS NULL OR NEW.provider IS NULL OR NEW.api IS NULL
  OR NEW.upstream_model IS NULL OR NEW.access_mode IS NULL OR NEW.config_generation IS NULL
BEGIN SELECT RAISE(ABORT, 'turn runtime snapshot is required'); END;
