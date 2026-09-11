-- @author kongweiguang
-- Thread 创建时冻结子智能体策略；全局设置只影响新建 Thread。
CREATE TABLE thread_subagent_policies (
    thread_id TEXT PRIMARY KEY NOT NULL,
    enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
    provider_id TEXT,
    model_id TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE CASCADE,
    CHECK ((provider_id IS NULL) = (model_id IS NULL)),
    CHECK (provider_id IS NULL OR (provider_id GLOB 'provider_*' AND model_id GLOB 'model_*'))
);

-- 旧库已有 Thread 无策略事实时只初始化一次；之后读取不到策略即视为损坏而失败关闭。
INSERT INTO thread_subagent_policies(thread_id,enabled,provider_id,model_id,created_at)
SELECT thread_id,1,NULL,NULL,created_at FROM threads;
