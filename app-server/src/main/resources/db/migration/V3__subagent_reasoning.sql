-- @author kongweiguang
-- 子智能体指定模型的思考档位；跟随父任务和旧快照均保持 NULL。
ALTER TABLE thread_subagent_policies ADD COLUMN reasoning_level TEXT CHECK (
    reasoning_level IS NULL OR reasoning_level IN ('off','minimal','low','medium','high','xhigh','max')
    AND (provider_id IS NOT NULL OR reasoning_level IS NULL)
);
