// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::FileRevision;

/// Watch generation 是 workspace switch 的竞态栅栏，所有增量与应答必须携带同一代号。
#[derive(Debug, Clone, Copy)]
pub(crate) struct WatchCommand {
    pub generation: u64,
}

/// Watch 事件只包含相对路径与 revision，原生绝对路径不得离开基础设施层。
#[derive(Debug, Clone)]
pub(crate) struct WorkspaceChange {
    pub relative_path: String,
    pub generation: u64,
    pub revision: Option<FileRevision>,
    pub requires_rescan: bool,
}

/// Start 返回实际活跃 generation；重复或旧 start 不会创建第二个 watcher。
#[derive(Debug, Clone, Copy)]
pub(crate) struct WatchStartResult {
    pub started: bool,
    pub generation: u64,
}

/// Stop 仅确认匹配 generation 的 worker 是否被回收。
#[derive(Debug, Clone, Copy)]
pub(crate) struct WatchStopResult {
    pub stopped: bool,
}

/// Rescan 结果显式暴露降级事实，避免 UI 把不完整增量当成权威快照。
#[derive(Debug, Clone, Copy)]
pub(crate) struct WatchRescanResult {
    pub generation: u64,
    pub requires_rescan: bool,
    pub emitted_paths: usize,
}
