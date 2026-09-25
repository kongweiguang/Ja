// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::app_runtime::RuntimeHost;
use crate::workspace::application::WorkspaceMutationService;
use crate::workspace::domain::{
    CreateEntryCommand, CreateEntryKind, CreateEntryResult, DropImportCommand, DropImportResult,
    FileSaveResult, MoveEntryCommand, MoveEntryResult, SaveFileCommand, TextContent,
    TrashCommitCommand, TrashCommitResult, TrashPrepareCommand, TrashPrepareResult,
};
use crate::workspace::infrastructure::NativeWorkspaceMutationPort;
use crate::workspace::{EntryKind, FileRevision, LineEnding, TextEncoding, WorkspaceError};
use serde::{Deserialize, Serialize};

use super::query::{WorkspaceCommandError, WorkspaceFileRevisionDto, with_workspace};

/// Wire revision 对 hash 长度设硬上限，避免未受限字符串进入 blocking worker 与幂等状态。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceFileRevisionInput {
    pub kind: EntryKind,
    pub size: u64,
    pub modified_unix_millis: Option<u128>,
    pub sha256: Option<String>,
}

/// Wire 文本显式声明编码与换行，interface 只做结构映射，不猜测磁盘格式。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceTextContentInput {
    pub text: String,
    pub encoding: TextEncoding,
    pub line_ending: LineEnding,
}

/// Create 的公开闭集与领域闭集一一映射，未知值由 serde 在 IPC 边界拒绝。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkspaceCreateEntryKind {
    File,
    Directory,
}

/// 创建输入只允许 opaque workspace id 与相对路径，不接受任何绝对目标。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceCreateEntryInput {
    pub workspace_id: String,
    pub relative_path: String,
    pub kind: WorkspaceCreateEntryKind,
    pub expected_revision: Option<WorkspaceFileRevisionInput>,
    pub mutation_id: String,
    pub content: Option<WorkspaceTextContentInput>,
}

/// 保存输入要求 expected revision，协议层不存在无 CAS 的覆盖路径。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceFileSaveInput {
    pub workspace_id: String,
    pub relative_path: String,
    pub expected_revision: WorkspaceFileRevisionInput,
    pub mutation_id: String,
    pub text: String,
    pub encoding: TextEncoding,
    pub line_ending: LineEnding,
}

/// Move 输入保留两个相对路径，canonical endpoint 永远不跨越 IPC。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceMoveEntryInput {
    pub workspace_id: String,
    pub from_relative_path: String,
    pub to_relative_path: String,
    pub expected_revision: WorkspaceFileRevisionInput,
    pub mutation_id: String,
}

/// Trash prepare 只创建一次性计划，删除本身由独立 commit 请求触发。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceTrashPrepareInput {
    pub workspace_id: String,
    pub relative_path: String,
    pub expected_revision: WorkspaceFileRevisionInput,
    pub mutation_id: String,
}

/// Trash commit 必须回传原请求证据，不能只凭 token 执行平台副作用。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceTrashCommitInput {
    pub workspace_id: String,
    pub relative_path: String,
    pub expected_revision: WorkspaceFileRevisionInput,
    pub operation_token: String,
    pub mutation_id: String,
}

/// Drop import 只传 Rust 签发的 token，不让 WebView 持有原生 source path。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceDropImportInput {
    pub workspace_id: String,
    pub destination_relative_path: String,
    pub expected_revision: WorkspaceFileRevisionInput,
    pub drop_token: String,
    pub mutation_id: String,
}

/// 保存结果只投影相对路径和权威 revision。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceFileSaveResultDto {
    pub relative_path: String,
    pub revision: WorkspaceFileRevisionDto,
}

/// 创建结果以原生落盘事实为准，不复用请求 kind。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceCreateEntryResultDto {
    pub relative_path: String,
    pub kind: EntryKind,
    pub revision: WorkspaceFileRevisionDto,
}

/// Move 结果保留旧/新路径供前端原子重映射。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceMoveEntryResultDto {
    pub from_relative_path: String,
    pub to_relative_path: String,
    pub revision: WorkspaceFileRevisionDto,
}

/// Trash 预览仅返回有界统计和短期 token，不暴露原生快照。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceTrashPrepareResultDto {
    pub operation_token: String,
    pub file_count: usize,
    pub total_bytes: u64,
    pub expires_at_unix_millis: u64,
}

/// Trash commit 成功事实保持路径脱敏。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceTrashCommitResultDto {
    pub committed: bool,
    pub revision: Option<WorkspaceFileRevisionDto>,
}

/// Drop import 只返回 Workspace-relative 路径。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceDropImportResultDto {
    pub imported_relative_paths: Vec<String>,
}

/// 将 wire revision 交给领域构造器，interface 不再维护第二套摘要规则。
fn revision(input: WorkspaceFileRevisionInput) -> Result<FileRevision, WorkspaceError> {
    FileRevision::try_new(
        input.kind,
        input.size,
        input.modified_unix_millis,
        input.sha256,
    )
}

/// 将领域 revision 投影成稳定 camelCase DTO。
fn revision_dto(value: FileRevision) -> WorkspaceFileRevisionDto {
    let (kind, size, modified_unix_millis, sha256) = value.into_parts();
    WorkspaceFileRevisionDto {
        kind,
        size,
        modified_unix_millis,
        sha256,
    }
}

/// 在已 admission 的 Workspace handle 上构造唯一 mutation service，避免 command 重复业务判断。
fn with_mutation_service<T>(
    host: &RuntimeHost,
    workspace_id: &str,
    operation: impl FnOnce(
        WorkspaceMutationService<NativeWorkspaceMutationPort>,
    ) -> Result<T, crate::workspace::WorkspaceError>,
) -> Result<T, WorkspaceCommandError> {
    with_workspace(host, workspace_id, |workspace| {
        operation(WorkspaceMutationService::new(
            NativeWorkspaceMutationPort::new(workspace.clone()),
        ))
    })
}

/// 创建 command 只校验 wire 预算、映射 DTO 并把 blocking IO 交给 application service。
#[tauri::command]
pub async fn ja_workspace_create_entry(
    input: WorkspaceCreateEntryInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceCreateEntryResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let expected_revision = input
            .expected_revision
            .map(revision)
            .transpose()
            .map_err(WorkspaceCommandError::from_workspace)?;
        let command = CreateEntryCommand::new(
            input.relative_path,
            match input.kind {
                WorkspaceCreateEntryKind::File => CreateEntryKind::File,
                WorkspaceCreateEntryKind::Directory => CreateEntryKind::Directory,
            },
            expected_revision,
            input.mutation_id,
            input.content.map(|value| TextContent {
                text: value.text,
                encoding: value.encoding,
                line_ending: value.line_ending,
            }),
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| {
            service.create_entry(command)
        })
        .map(project_create)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// 保存 command 只负责协议校验和 DTO 投影，CAS/原子写/回滚只有 infrastructure 一份实现。
#[tauri::command]
pub async fn ja_workspace_save_file(
    input: WorkspaceFileSaveInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceFileSaveResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let command = SaveFileCommand::new(
            input.relative_path,
            revision(input.expected_revision).map_err(WorkspaceCommandError::from_workspace)?,
            input.mutation_id,
            TextContent {
                text: input.text,
                encoding: input.encoding,
                line_ending: input.line_ending,
            },
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| service.save_file(command))
            .map(project_save)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Move command 只转换两个相对路径，端点 containment 与锁顺序由原生端口决定。
#[tauri::command]
pub async fn ja_workspace_move_entry(
    input: WorkspaceMoveEntryInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceMoveEntryResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let command = MoveEntryCommand::new(
            input.from_relative_path,
            input.to_relative_path,
            revision(input.expected_revision).map_err(WorkspaceCommandError::from_workspace)?,
            input.mutation_id,
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| service.move_entry(command))
            .map(project_move)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Trash prepare command 只创建短期 capability，不执行删除。
#[tauri::command]
pub async fn ja_workspace_trash_prepare(
    input: WorkspaceTrashPrepareInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceTrashPrepareResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let command = TrashPrepareCommand::new(
            input.relative_path,
            revision(input.expected_revision).map_err(WorkspaceCommandError::from_workspace)?,
            input.mutation_id,
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| {
            service.prepare_trash(command)
        })
        .map(project_trash_prepare)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Trash commit command 保证 token 有界，再把平台副作用事务交给 application/infrastructure。
#[tauri::command]
pub async fn ja_workspace_trash_commit(
    input: WorkspaceTrashCommitInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceTrashCommitResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let command = TrashCommitCommand::new(
            input.relative_path,
            revision(input.expected_revision).map_err(WorkspaceCommandError::from_workspace)?,
            input.operation_token,
            input.mutation_id,
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| {
            service.commit_trash(command)
        })
        .map(project_trash_commit)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// Drop command 只接收 opaque token；原生 source path 不经过 DTO 或 application。
#[tauri::command]
pub async fn ja_workspace_import_drop(
    input: WorkspaceDropImportInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceDropImportResultDto, WorkspaceCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let command = DropImportCommand::new(
            input.destination_relative_path,
            revision(input.expected_revision).map_err(WorkspaceCommandError::from_workspace)?,
            input.drop_token,
            input.mutation_id,
        )
        .map_err(WorkspaceCommandError::from_workspace)?;
        with_mutation_service(&host, &workspace_id, |service| service.import_drop(command))
            .map(project_drop)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// 将创建领域结果投影为 wire DTO。
fn project_create(value: CreateEntryResult) -> WorkspaceCreateEntryResultDto {
    WorkspaceCreateEntryResultDto {
        relative_path: value.relative_path,
        kind: value.kind,
        revision: revision_dto(value.revision),
    }
}

/// 将保存领域结果投影为 wire DTO。
fn project_save(value: FileSaveResult) -> WorkspaceFileSaveResultDto {
    WorkspaceFileSaveResultDto {
        relative_path: value.relative_path,
        revision: revision_dto(value.revision),
    }
}

/// 将 Move 领域结果投影为 wire DTO。
fn project_move(value: MoveEntryResult) -> WorkspaceMoveEntryResultDto {
    WorkspaceMoveEntryResultDto {
        from_relative_path: value.from_relative_path,
        to_relative_path: value.to_relative_path,
        revision: revision_dto(value.revision),
    }
}

/// 将 Trash prepare 领域结果投影为 wire DTO，不携带内部 snapshot。
fn project_trash_prepare(value: TrashPrepareResult) -> WorkspaceTrashPrepareResultDto {
    WorkspaceTrashPrepareResultDto {
        operation_token: value.operation_token,
        file_count: value.file_count,
        total_bytes: value.total_bytes,
        expires_at_unix_millis: value.expires_at_unix_millis,
    }
}

/// 将 Trash commit 领域结果投影为 wire DTO。
fn project_trash_commit(value: TrashCommitResult) -> WorkspaceTrashCommitResultDto {
    WorkspaceTrashCommitResultDto {
        committed: value.committed,
        revision: value.revision.map(revision_dto),
    }
}

/// 将 Drop 领域结果投影为相对路径列表。
fn project_drop(value: DropImportResult) -> WorkspaceDropImportResultDto {
    WorkspaceDropImportResultDto {
        imported_relative_paths: value.imported_relative_paths,
    }
}
