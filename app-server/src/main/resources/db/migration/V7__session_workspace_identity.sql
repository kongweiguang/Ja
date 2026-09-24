-- @author kongweiguang
-- Workspace 类型由 V7 明确落库；旧共享行在 Java 启动事务中按 thread lineage 分拆。
ALTER TABLE workspaces ADD COLUMN kind TEXT NOT NULL DEFAULT 'PROJECT'
    CHECK (kind IN ('PROJECT','SESSION','LEGACY_SHARED'));
ALTER TABLE workspaces ADD COLUMN legacy_shared_workspace_id TEXT
    REFERENCES workspaces(workspace_id) ON DELETE RESTRICT;
