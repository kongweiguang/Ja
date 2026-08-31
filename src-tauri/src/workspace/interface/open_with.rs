// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::app_runtime::{RuntimeHost, WorkspaceLookup};
use crate::workspace::application::WorkspaceOpenService;
use crate::workspace::domain::{OpenError, OpenResult, OpenTargetAvailability};
use crate::workspace::infrastructure::NativeWorkspaceOpenPort;
use crate::workspace::{
    EntryKind, OpenTargetUnavailableReason, OpenWithTarget, WorkspaceError, WorkspaceHandle,
};
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

use super::query::validate_workspace_id;

/// 单个目标可用性 DTO 丢弃 executable path，只保留稳定产品名与原因。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceOpenTargetDto {
    pub target: OpenWithTarget,
    pub display_name: &'static str,
    pub available: bool,
    pub reason: Option<OpenTargetUnavailableReason>,
}

/// 闭集发现结果便于 UI 一次渲染，不产生逐目标 IPC 竞态。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceOpenTargetsDto {
    pub targets: Vec<WorkspaceOpenTargetDto>,
}

/// Target discovery 只接受协议 Workspace id。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceOpenTargetsInput {
    pub workspace_id: String,
}

/// Open 请求以字符串接收 target，以便未知枚举映射为稳定 INVALID_INPUT 而非 serde 细节。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceOpenInput {
    pub workspace_id: String,
    pub target: String,
    #[serde(default)]
    pub relative_path: Option<String>,
}

/// Launch acknowledgement 不公开 executable、argv、PID 或绝对路径。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceOpenResultDto {
    pub opened: bool,
    pub target: OpenWithTarget,
    pub relative_path: String,
    pub entry_kind: EntryKind,
}

/// Open command 的稳定错误闭集只表达 UI 可采取的恢复动作。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceOpenCommandErrorCode {
    NotConfigured,
    UnknownWorkspace,
    InvalidInput,
    InvalidPath,
    PathRejected,
    NotFound,
    NotOpenable,
    TargetUnavailable,
    LaunchFailed,
}

/// IPC error 只保留稳定 code；原生路径、候选程序和 OS 诊断不会进入 Display。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct WorkspaceOpenCommandError {
    pub code: WorkspaceOpenCommandErrorCode,
}

impl WorkspaceOpenCommandError {
    /// 在触及 Workspace 或进程前构造唯一输入错误。
    const fn invalid_input() -> Self {
        Self {
            code: WorkspaceOpenCommandErrorCode::InvalidInput,
        }
    }

    /// 将领域/原生失败收窄为路径脱敏的 IPC code。
    fn from_open(error: OpenError) -> Self {
        let code = match error {
            OpenError::InvalidInput => WorkspaceOpenCommandErrorCode::InvalidInput,
            OpenError::NotOpenable => WorkspaceOpenCommandErrorCode::NotOpenable,
            OpenError::TargetUnavailable => WorkspaceOpenCommandErrorCode::TargetUnavailable,
            OpenError::LaunchFailed => WorkspaceOpenCommandErrorCode::LaunchFailed,
            OpenError::Workspace(error) => match error {
                WorkspaceError::InvalidRelativePath => WorkspaceOpenCommandErrorCode::InvalidPath,
                WorkspaceError::OutsideWorkspace
                | WorkspaceError::PathChanged
                | WorkspaceError::LinkNotAllowed
                | WorkspaceError::InvalidRoot => WorkspaceOpenCommandErrorCode::PathRejected,
                WorkspaceError::PathNotFound => WorkspaceOpenCommandErrorCode::NotFound,
                WorkspaceError::NotDirectory | WorkspaceError::NotFile => {
                    WorkspaceOpenCommandErrorCode::NotOpenable
                }
                WorkspaceError::WorkspaceNotFound => {
                    WorkspaceOpenCommandErrorCode::UnknownWorkspace
                }
                _ => WorkspaceOpenCommandErrorCode::LaunchFailed,
            },
        };
        Self { code }
    }

    /// Blocking worker 终止统一映射为 launch failed，不暴露 panic 或 runtime 状态。
    const fn blocking_worker_failed() -> Self {
        Self {
            code: WorkspaceOpenCommandErrorCode::LaunchFailed,
        }
    }
}

impl Display for WorkspaceOpenCommandError {
    /// 返回固定文本，确保 Tauri rejection 不含安装路径、argv 或 OS 错误。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code {
            WorkspaceOpenCommandErrorCode::NotConfigured => "workspace is not configured",
            WorkspaceOpenCommandErrorCode::UnknownWorkspace => "workspace is unknown",
            WorkspaceOpenCommandErrorCode::InvalidInput => "open request is invalid",
            WorkspaceOpenCommandErrorCode::InvalidPath => "workspace path is invalid",
            WorkspaceOpenCommandErrorCode::PathRejected => "workspace path is rejected",
            WorkspaceOpenCommandErrorCode::NotFound => "workspace entry was not found",
            WorkspaceOpenCommandErrorCode::NotOpenable => "workspace entry cannot be opened",
            WorkspaceOpenCommandErrorCode::TargetUnavailable => "open target is unavailable",
            WorkspaceOpenCommandErrorCode::LaunchFailed => "open target failed to start",
        })
    }
}

impl std::error::Error for WorkspaceOpenCommandError {}

/// 在活动 RuntimeHost binding 上运行 open service，并独立映射未配置/未知 Workspace。
fn with_open_workspace<T>(
    host: &RuntimeHost,
    workspace_id: &str,
    operation: impl FnOnce(&WorkspaceHandle) -> Result<T, OpenError>,
) -> Result<T, WorkspaceOpenCommandError> {
    if !validate_workspace_id(workspace_id) {
        return Err(WorkspaceOpenCommandError::invalid_input());
    }
    host.with_configured_workspace(workspace_id, operation)
        .map_err(|lookup| WorkspaceOpenCommandError {
            code: match lookup {
                WorkspaceLookup::Unconfigured => WorkspaceOpenCommandErrorCode::NotConfigured,
                WorkspaceLookup::Unknown => WorkspaceOpenCommandErrorCode::UnknownWorkspace,
            },
        })?
        .map_err(WorkspaceOpenCommandError::from_open)
}

/// Target discovery command 只做 binding、blocking 调度与 DTO 投影。
#[tauri::command]
pub async fn ja_workspace_open_targets(
    input: WorkspaceOpenTargetsInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceOpenTargetsDto, WorkspaceOpenCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_open_workspace(&host, &input.workspace_id, |workspace| {
            WorkspaceOpenService::new(NativeWorkspaceOpenPort::new(workspace.clone())).targets()
        })
        .map(|targets| WorkspaceOpenTargetsDto {
            targets: targets.into_iter().map(project_target).collect(),
        })
    })
    .await
    .map_err(|_| WorkspaceOpenCommandError::blocking_worker_failed())?
}

/// Open command 在阻塞池调用闭集 target，interface 不构造 executable 或参数。
#[tauri::command]
pub async fn ja_workspace_open(
    input: WorkspaceOpenInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceOpenResultDto, WorkspaceOpenCommandError> {
    let Some(target) = OpenWithTarget::parse(&input.target) else {
        return Err(WorkspaceOpenCommandError::invalid_input());
    };
    let workspace_id = input.workspace_id;
    let relative_path = input.relative_path.unwrap_or_default();
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_open_workspace(&host, &workspace_id, |workspace| {
            WorkspaceOpenService::new(NativeWorkspaceOpenPort::new(workspace.clone()))
                .open(target, relative_path)
        })
        .map(project_result)
    })
    .await
    .map_err(|_| WorkspaceOpenCommandError::blocking_worker_failed())?
}

/// 投影单个目标时补充领域产品名，仍不公开 resolver 选择的路径。
fn project_target(value: OpenTargetAvailability) -> WorkspaceOpenTargetDto {
    WorkspaceOpenTargetDto {
        target: value.target,
        display_name: value.target.display_name(),
        available: value.available,
        reason: value.reason,
    }
}

/// 投影 launch 成功事实，opened 只在原生 process creation 成功后为 true。
fn project_result(value: OpenResult) -> WorkspaceOpenResultDto {
    WorkspaceOpenResultDto {
        opened: true,
        target: value.target,
        relative_path: value.relative_path,
        entry_kind: value.entry_kind,
    }
}
