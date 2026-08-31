// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::app_runtime::RuntimeHost;
use crate::workspace::application::WorkspaceWatchService;
use crate::workspace::domain::{
    WatchCommand, WatchRescanResult, WatchStartResult, WatchStopResult, WorkspaceChange,
};
use crate::workspace::infrastructure::{NativeWorkspaceWatchPort, WorkspaceWatchEventSink};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tauri::Emitter;

use super::query::{WorkspaceCommandError, WorkspaceFileRevisionDto, with_workspace};

/// Start DTO 只携带协议 Workspace id 与 UI generation 栅栏。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceWatchStartInput {
    pub workspace_id: String,
    pub generation: u64,
}

/// Stop DTO 必须携带 generation，防止晚到请求关闭新 session。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceWatchStopInput {
    pub workspace_id: String,
    pub generation: u64,
}

/// Rescan DTO 使用当前 generation 对账，不把单个 native event 当完整事实。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceWatchRescanInput {
    pub workspace_id: String,
    pub generation: u64,
}

/// Event DTO 只投影相对路径与 revision，绝对路径永不进入 WebView。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceChangedEventDto {
    pub relative_path: String,
    pub generation: u64,
    pub revision: Option<WorkspaceFileRevisionDto>,
    pub requires_rescan: bool,
}

/// Start acknowledgement 返回真实活跃 generation。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceWatchStartResultDto {
    pub started: bool,
    pub generation: u64,
}

/// Stop acknowledgement 只表达匹配 session 是否已回收。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceWatchStopResultDto {
    pub stopped: bool,
}

/// Rescan acknowledgement 显式保留 requires-rescan 降级事实。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceWatchRescanResultDto {
    pub generation: u64,
    pub requires_rescan: bool,
    pub emitted_paths: usize,
}

/// 把领域事件映射到唯一 Tauri event；发送失败映射为路径脱敏 IO 错误供 watcher 降级恢复。
fn event_sink(app: tauri::AppHandle) -> WorkspaceWatchEventSink {
    Arc::new(move |change| {
        app.emit("ja://workspace-changed", project_change(change))
            .map_err(|_| {
                crate::workspace::WorkspaceError::io("watch_event", std::io::ErrorKind::Other)
            })
    })
}

/// 将领域 change 投影为稳定 camelCase DTO。
fn project_change(change: WorkspaceChange) -> WorkspaceChangedEventDto {
    WorkspaceChangedEventDto {
        relative_path: change.relative_path,
        generation: change.generation,
        revision: change.revision.map(|revision| {
            let (kind, size, modified_unix_millis, sha256) = revision.into_parts();
            WorkspaceFileRevisionDto {
                kind,
                size,
                modified_unix_millis,
                sha256,
            }
        }),
        requires_rescan: change.requires_rescan,
    }
}

/// Start command 只完成 RuntimeHost admission、DTO 映射与 blocking 调度。
#[tauri::command]
pub async fn ja_workspace_watch_start(
    input: WorkspaceWatchStartInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceWatchStartResultDto, WorkspaceCommandError> {
    let workspace_id = input.workspace_id;
    let generation = input.generation;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_workspace(&host, &workspace_id, |workspace| {
            WorkspaceWatchService::new(NativeWorkspaceWatchPort::new(
                workspace.clone(),
                event_sink(app),
            ))
            .start(WatchCommand { generation })
        })
        .map(project_start)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Stop command 不创建 event sink，匹配 generation 后同步等待 worker 释放再应答。
#[tauri::command]
pub async fn ja_workspace_watch_stop(
    input: WorkspaceWatchStopInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceWatchStopResultDto, WorkspaceCommandError> {
    let workspace_id = input.workspace_id;
    let generation = input.generation;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_workspace(&host, &workspace_id, |workspace| {
            let sink: WorkspaceWatchEventSink = Arc::new(|_| Ok(()));
            WorkspaceWatchService::new(NativeWorkspaceWatchPort::new(workspace.clone(), sink))
                .stop(WatchCommand { generation })
        })
        .map(project_stop)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Rescan command 注入单一 event sink，扫描前后 generation 复核由 application port 保证。
#[tauri::command]
pub async fn ja_workspace_watch_rescan(
    input: WorkspaceWatchRescanInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceWatchRescanResultDto, WorkspaceCommandError> {
    let workspace_id = input.workspace_id;
    let generation = input.generation;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_workspace(&host, &workspace_id, |workspace| {
            WorkspaceWatchService::new(NativeWorkspaceWatchPort::new(
                workspace.clone(),
                event_sink(app),
            ))
            .rescan(WatchCommand { generation })
        })
        .map(project_rescan)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// 将 start 领域结果投影为 wire DTO。
fn project_start(value: WatchStartResult) -> WorkspaceWatchStartResultDto {
    WorkspaceWatchStartResultDto {
        started: value.started,
        generation: value.generation,
    }
}

/// 将 stop 领域结果投影为 wire DTO。
fn project_stop(value: WatchStopResult) -> WorkspaceWatchStopResultDto {
    WorkspaceWatchStopResultDto {
        stopped: value.stopped,
    }
}

/// 将 rescan 领域结果投影为 wire DTO，并保留降级统计。
fn project_rescan(value: WatchRescanResult) -> WorkspaceWatchRescanResultDto {
    WorkspaceWatchRescanResultDto {
        generation: value.generation,
        requires_rescan: value.requires_rescan,
        emitted_paths: value.emitted_paths,
    }
}
