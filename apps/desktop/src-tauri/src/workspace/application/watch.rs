// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::WorkspaceError;
use crate::workspace::domain::{
    WatchCommand, WatchRescanResult, WatchStartResult, WatchStopResult,
};

/// Watch port 隔离 notify、线程和事件 sink，application 只编排 generation 生命周期。
pub(crate) trait WorkspaceWatchPort {
    fn start(&self, command: WatchCommand) -> Result<WatchStartResult, WorkspaceError>;
    fn stop(&self, command: WatchCommand) -> Result<WatchStopResult, WorkspaceError>;
    fn rescan(&self, command: WatchCommand) -> Result<WatchRescanResult, WorkspaceError>;
}

/// Watch service 是 start/stop/reconciliation 的唯一用例入口，不直接依赖 Tauri 或 notify。
pub(crate) struct WorkspaceWatchService<P> {
    port: P,
}

impl<P: WorkspaceWatchPort> WorkspaceWatchService<P> {
    /// 注入绑定单个 Workspace 与 event sink 的 port，防止一次调用跨 workspace switch 取新 owner。
    pub(crate) fn new(port: P) -> Self {
        Self { port }
    }

    /// Start 的单调 admission、旧 session 回收和 generation 去重由端口的唯一状态机实现。
    pub(crate) fn start(&self, command: WatchCommand) -> Result<WatchStartResult, WorkspaceError> {
        self.port.start(command)
    }

    /// Stop 只允许匹配 generation 的调用回收 worker，晚到 stop 不得关闭新 workspace watcher。
    pub(crate) fn stop(&self, command: WatchCommand) -> Result<WatchStopResult, WorkspaceError> {
        self.port.stop(command)
    }

    /// Rescan 必须在同一 generation 前后复核 session，避免旧扫描结果污染新 Workspace。
    pub(crate) fn rescan(
        &self,
        command: WatchCommand,
    ) -> Result<WatchRescanResult, WorkspaceError> {
        self.port.rescan(command)
    }
}
