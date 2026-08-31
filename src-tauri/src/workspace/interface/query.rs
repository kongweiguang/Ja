// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace 只读服务的轻量 Tauri 适配层。
//
// command 层只负责线协议校验与错误投影；`WorkspaceRegistry`、`TreeReader`、
// `FileReader` 和 `TextSearch` 仍是路径 containment 与有界 IO 的唯一实现。

use crate::app_runtime::{RuntimeHost, WorkspaceLookup};
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

use crate::workspace::application::{WorkspaceQueryService, WorkspaceSearchResult};
use crate::workspace::infrastructure::NativeWorkspaceQueryPort;
use crate::workspace::{
    ContentKind, EntryKind, FileContent, FileMetadata, FileRevision, LineEnding, SearchHit,
    TextEncoding, TreeEntry, TreePage, TreePageRequest, WorkspaceError,
};

/// 稳定的 Workspace command 错误闭集阻止原生路径和 IO 诊断进入 WebView，
/// 同时保留前端判断重试或恢复动作所需的最小语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceCommandErrorCode {
    NotConfigured,
    UnknownWorkspace,
    InvalidInput,
    InvalidPath,
    PathRejected,
    NotFound,
    NotDirectory,
    NotFile,
    StaleCursor,
    LimitExceeded,
    ChangedDuringRead,
    AlreadyExists,
    RevisionConflict,
    WorkspaceRecoveryRequired,
    MutationAlreadyUsed,
    InvalidMutationId,
    UnsupportedContent,
    TrashTokenInvalid,
    TrashTokenExpired,
    RecycleUnavailable,
    DropTokenInvalid,
    WatchUnavailable,
    Io,
}

/// 错误 DTO 刻意不包含绝对路径、操作系统消息或调用栈，避免 Tauri rejection 泄露原生细节。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct WorkspaceCommandError {
    pub code: WorkspaceCommandErrorCode,
}

impl WorkspaceCommandError {
    /// 在 reader 观察到无界输入前返回稳定错误，避免把非法内容带入文件系统层。
    pub(crate) const fn invalid_input() -> Self {
        Self {
            code: WorkspaceCommandErrorCode::InvalidInput,
        }
    }

    /// 将 service 失败收窄为 UI 可恢复的稳定类别，避免泄露路径与底层 IO。
    pub(crate) fn from_workspace(error: WorkspaceError) -> Self {
        let code = match error {
            WorkspaceError::InvalidRelativePath => WorkspaceCommandErrorCode::InvalidPath,
            WorkspaceError::InvalidRevision => WorkspaceCommandErrorCode::InvalidInput,
            WorkspaceError::OutsideWorkspace
            | WorkspaceError::PathChanged
            | WorkspaceError::LinkNotAllowed => WorkspaceCommandErrorCode::PathRejected,
            WorkspaceError::PathNotFound => WorkspaceCommandErrorCode::NotFound,
            WorkspaceError::NotDirectory => WorkspaceCommandErrorCode::NotDirectory,
            WorkspaceError::NotFile => WorkspaceCommandErrorCode::NotFile,
            WorkspaceError::StaleCursor => WorkspaceCommandErrorCode::StaleCursor,
            WorkspaceError::EntryBudgetExceeded
            | WorkspaceError::DepthLimitExceeded
            | WorkspaceError::ScanDeadlineExceeded
            | WorkspaceError::FileTooLarge => WorkspaceCommandErrorCode::LimitExceeded,
            WorkspaceError::ChangedDuringRead => WorkspaceCommandErrorCode::ChangedDuringRead,
            WorkspaceError::AlreadyExists => WorkspaceCommandErrorCode::AlreadyExists,
            WorkspaceError::RevisionConflict => WorkspaceCommandErrorCode::RevisionConflict,
            WorkspaceError::RecoveryRequired => {
                WorkspaceCommandErrorCode::WorkspaceRecoveryRequired
            }
            WorkspaceError::MutationAlreadyUsed => WorkspaceCommandErrorCode::MutationAlreadyUsed,
            WorkspaceError::InvalidMutationId => WorkspaceCommandErrorCode::InvalidMutationId,
            WorkspaceError::UnsupportedContent | WorkspaceError::MixedLineEndings => {
                WorkspaceCommandErrorCode::UnsupportedContent
            }
            WorkspaceError::WriteTooLarge => WorkspaceCommandErrorCode::LimitExceeded,
            WorkspaceError::TrashTokenInvalid => WorkspaceCommandErrorCode::TrashTokenInvalid,
            WorkspaceError::TrashTokenExpired => WorkspaceCommandErrorCode::TrashTokenExpired,
            WorkspaceError::RecycleUnavailable => WorkspaceCommandErrorCode::RecycleUnavailable,
            WorkspaceError::DropTokenInvalid => WorkspaceCommandErrorCode::DropTokenInvalid,
            WorkspaceError::WatchUnavailable | WorkspaceError::WatchShutdownTimeout => {
                WorkspaceCommandErrorCode::WatchUnavailable
            }
            WorkspaceError::InvalidRoot | WorkspaceError::WorkspaceNotFound => {
                WorkspaceCommandErrorCode::UnknownWorkspace
            }
            WorkspaceError::Io { .. } => WorkspaceCommandErrorCode::Io,
        };
        Self { code }
    }

    /// 将 blocking worker 终止收窄为 opaque IO，避免 panic 或 runtime shutdown 泄露原生细节。
    pub(crate) const fn blocking_worker_failed() -> Self {
        Self {
            code: WorkspaceCommandErrorCode::Io,
        }
    }
}

impl Display for WorkspaceCommandError {
    /// 为 Tauri error path 输出稳定且无路径的诊断，不复述底层错误。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code {
            WorkspaceCommandErrorCode::NotConfigured => "workspace is not configured",
            WorkspaceCommandErrorCode::UnknownWorkspace => "workspace is unknown",
            WorkspaceCommandErrorCode::InvalidInput => "workspace request is invalid",
            WorkspaceCommandErrorCode::InvalidPath => "workspace path is invalid",
            WorkspaceCommandErrorCode::PathRejected => "workspace path is rejected",
            WorkspaceCommandErrorCode::NotFound => "workspace entry was not found",
            WorkspaceCommandErrorCode::NotDirectory => "workspace entry is not a directory",
            WorkspaceCommandErrorCode::NotFile => "workspace entry is not a file",
            WorkspaceCommandErrorCode::StaleCursor => "workspace cursor is stale",
            WorkspaceCommandErrorCode::LimitExceeded => "workspace request exceeded its limit",
            WorkspaceCommandErrorCode::ChangedDuringRead => "workspace entry changed during read",
            WorkspaceCommandErrorCode::Io => "workspace operation failed",
            WorkspaceCommandErrorCode::AlreadyExists => "workspace entry already exists",
            WorkspaceCommandErrorCode::RevisionConflict => {
                "workspace entry changed; reload before saving"
            }
            WorkspaceCommandErrorCode::WorkspaceRecoveryRequired => {
                "workspace recovery is required before continuing"
            }
            WorkspaceCommandErrorCode::MutationAlreadyUsed => "workspace mutation was already used",
            WorkspaceCommandErrorCode::InvalidMutationId => "workspace mutation id is invalid",
            WorkspaceCommandErrorCode::UnsupportedContent => "workspace content cannot be written",
            WorkspaceCommandErrorCode::TrashTokenInvalid => "trash operation is invalid",
            WorkspaceCommandErrorCode::TrashTokenExpired => "trash operation expired",
            WorkspaceCommandErrorCode::RecycleUnavailable => {
                "system recycle bin is unavailable for this volume"
            }
            WorkspaceCommandErrorCode::DropTokenInvalid => "native drop is invalid or expired",
            WorkspaceCommandErrorCode::WatchUnavailable => "workspace watcher is unavailable",
        })
    }
}

impl std::error::Error for WorkspaceCommandError {}

/// camelCase 投影隔离内部服务快照，只把经过预算约束的 metadata 字段写入 IPC 合同。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceFileRevisionDto {
    pub kind: EntryKind,
    pub size: u64,
    pub modified_unix_millis: Option<u128>,
    pub sha256: Option<String>,
}

/// tree 与 file command 复用同一 camelCase metadata 投影，避免字段语义漂移。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceFileMetadataDto {
    pub kind: EntryKind,
    pub size: u64,
    pub modified_unix_millis: Option<u128>,
    pub revision: WorkspaceFileRevisionDto,
}

/// 单个 tree 子节点只携带虚拟化 React 文件浏览器需要的相对路径与有界 metadata。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceTreeEntryDto {
    pub name: String,
    pub relative_path: String,
    pub metadata: WorkspaceFileMetadataDto,
    pub can_expand: bool,
}

/// 单页 tree 结果使用不透明 cursor 与 snapshot token，防止调用方绕过分页边界。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceTreePageDto {
    pub entries: Vec<WorkspaceTreeEntryDto>,
    pub directory_revision: WorkspaceFileRevisionDto,
    pub next_cursor: Option<String>,
    pub snapshot_token: String,
    pub total_entries: usize,
    pub depth: usize,
}

/// 单文件投影显式区分 binary 与 encoding，避免 interface 猜测或替换内容。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceFileContentDto {
    pub metadata: WorkspaceFileMetadataDto,
    pub kind: ContentKind,
    pub encoding: Option<TextEncoding>,
    pub line_ending: Option<LineEnding>,
    pub text: Option<String>,
    pub bytes_read: usize,
    pub truncated: bool,
}

/// 单条字面量搜索命中使用前端稳定的行列字段名，不暴露 reader 内部表示。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceSearchHitDto {
    pub relative_path: String,
    pub line: usize,
    pub column: usize,
    pub snippet: String,
    pub encoding: TextEncoding,
}

/// 搜索结果显式携带预算与截断状态，UI 不得把有界局部结果展示成完整事实。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceSearchResultDto {
    pub hits: Vec<WorkspaceSearchHitDto>,
    pub truncated: bool,
    pub scanned_entries: usize,
    pub skipped_files: usize,
}

/// 将内部 revision 投影为 camelCase command DTO，CAS 字段不做裁剪。
fn project_revision(revision: FileRevision) -> WorkspaceFileRevisionDto {
    let (kind, size, modified_unix_millis, sha256) = revision.into_parts();
    WorkspaceFileRevisionDto {
        kind,
        size,
        modified_unix_millis,
        sha256,
    }
}

/// 统一投影 metadata，避免 tree 与 file 两条接口产生字段漂移。
fn project_metadata(metadata: FileMetadata) -> WorkspaceFileMetadataDto {
    WorkspaceFileMetadataDto {
        kind: metadata.kind,
        size: metadata.size,
        modified_unix_millis: metadata.modified_unix_millis,
        revision: project_revision(metadata.revision),
    }
}

/// 投影有界 tree page，不暴露内部路径或 reader 类型。
pub(crate) fn project_tree(page: TreePage) -> WorkspaceTreePageDto {
    WorkspaceTreePageDto {
        entries: page
            .entries
            .into_iter()
            .map(|entry: TreeEntry| WorkspaceTreeEntryDto {
                name: entry.name,
                relative_path: entry.relative_path,
                metadata: project_metadata(entry.metadata),
                can_expand: entry.can_expand,
            })
            .collect(),
        directory_revision: project_revision(page.directory_revision),
        next_cursor: page.next_cursor,
        snapshot_token: page.snapshot_token,
        total_entries: page.total_entries,
        depth: page.depth,
    }
}

/// 投影文件字节分类；binary 保持正文缺失，不尝试编码猜测。
fn project_file(content: FileContent) -> WorkspaceFileContentDto {
    WorkspaceFileContentDto {
        metadata: project_metadata(content.metadata),
        kind: content.kind,
        encoding: content.encoding,
        line_ending: content.line_ending,
        text: content.text,
        bytes_read: content.bytes_read,
        truncated: content.truncated,
    }
}

/// 将搜索 hit 与预算计数投影为 camelCase wire 字段，保留截断事实。
fn project_search(result: WorkspaceSearchResult) -> WorkspaceSearchResultDto {
    WorkspaceSearchResultDto {
        hits: result
            .hits
            .into_iter()
            .map(|hit: SearchHit| WorkspaceSearchHitDto {
                relative_path: hit.relative_path,
                line: hit.line,
                column: hit.column,
                snippet: hit.snippet,
                encoding: hit.encoding,
            })
            .collect(),
        truncated: result.truncated,
        scanned_entries: result.scanned_entries,
        skipped_files: result.skipped_files,
    }
}

/// 目录分页输入只表达当前已配置根下的一次有界读取，不允许传入原生根路径。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceTreeInput {
    pub workspace_id: String,
    pub relative_path: String,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub page_size: Option<usize>,
    #[serde(default)]
    pub snapshot_token: Option<String>,
}

/// 文件读取输入只请求一个有界常规文件投影，不让原生路径跨越 IPC。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceReadFileInput {
    pub workspace_id: String,
    pub relative_path: String,
}

/// 搜索输入限定为相对目录下的有界字面量查询，不在 interface 提供正则或任意扫描策略。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceSearchInput {
    pub workspace_id: String,
    pub relative_path: String,
    pub query: String,
}

/// 校验协议 workspace id；它与 `WorkspaceHandle` 内部 UUID 是两种独立身份。
pub(crate) fn validate_workspace_id(value: &str) -> bool {
    value.starts_with("ws_")
        && value.len() <= 99
        && value.len() > 3
        && value[3..]
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
}

/// reader 执行前应用统一有界字符串策略，拒绝控制字符与超长输入。
pub(crate) fn validate_relative_path(value: &str) -> bool {
    value.len() <= 4096 && !value.chars().any(char::is_control)
}

/// 将 host workspace lookup 投影到 command 稳定错误空间，不泄露 capability 状态。
fn map_lookup(error: WorkspaceLookup) -> WorkspaceCommandError {
    WorkspaceCommandError {
        code: match error {
            WorkspaceLookup::Unconfigured => WorkspaceCommandErrorCode::NotConfigured,
            WorkspaceLookup::Unknown => WorkspaceCommandErrorCode::UnknownWorkspace,
        },
    }
}

/// 仅在 host 持有活动协议绑定时运行 reader，使 configure 切换线性化后旧 handle 立即失效。
pub(crate) fn with_workspace<T>(
    state: &RuntimeHost,
    workspace_id: &str,
    operation: impl FnOnce(&crate::workspace::WorkspaceHandle) -> Result<T, WorkspaceError>,
) -> Result<T, WorkspaceCommandError> {
    if !validate_workspace_id(workspace_id) {
        return Err(WorkspaceCommandError::invalid_input());
    }
    state
        .with_configured_workspace(workspace_id, operation)
        .map_err(map_lookup)?
        .map_err(WorkspaceCommandError::from_workspace)
}

/// 在 Tauri blocking pool 经有界 `TreeReader` 读取目录 page，避免 NTFS 枚举和摘要阻塞 async executor。
#[tauri::command]
pub async fn ja_workspace_tree(
    input: WorkspaceTreeInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceTreePageDto, WorkspaceCommandError> {
    if !validate_relative_path(&input.relative_path)
        || input.cursor.as_ref().is_some_and(|value| value.len() > 256)
        || input
            .snapshot_token
            .as_ref()
            .is_some_and(|value| value.len() > 128)
    {
        return Err(WorkspaceCommandError::invalid_input());
    }
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let workspace_id = input.workspace_id;
        let request = TreePageRequest {
            relative_path: input.relative_path,
            cursor: input.cursor,
            page_size: input.page_size,
            snapshot_token: input.snapshot_token,
        };
        with_workspace(&host, &workspace_id, |workspace| {
            WorkspaceQueryService::new(NativeWorkspaceQueryPort::new(workspace.clone()))
                .tree(&request)
        })
        .map(project_tree)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// 在 blocking pool 使用有界编码/binary classifier 读取文件，避免同步文件 IO 阻塞 WebView2 命令调度。
#[tauri::command]
pub async fn ja_workspace_read_file(
    input: WorkspaceReadFileInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceFileContentDto, WorkspaceCommandError> {
    if !validate_relative_path(&input.relative_path) {
        return Err(WorkspaceCommandError::invalid_input());
    }
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_workspace(&host, &input.workspace_id, |workspace| {
            WorkspaceQueryService::new(NativeWorkspaceQueryPort::new(workspace.clone()))
                .read_file(&input.relative_path)
        })
        .map(project_file)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}

/// 在 Tauri blocking pool 执行字面量搜索，避免有界 NTFS 遍历阻塞 WebView2 输入与绘制。
#[tauri::command]
pub async fn ja_workspace_search(
    input: WorkspaceSearchInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceSearchResultDto, WorkspaceCommandError> {
    if !validate_relative_path(&input.relative_path)
        || input.query.is_empty()
        || input.query.len() > 8192
        || input.query.chars().any(char::is_control)
    {
        return Err(WorkspaceCommandError::invalid_input());
    }
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        with_workspace(&host, &input.workspace_id, |workspace| {
            WorkspaceQueryService::new(NativeWorkspaceQueryPort::new(workspace.clone()))
                .search(&input.relative_path, &input.query)
        })
        .map(project_search)
    })
    .await
    .map_err(|_| WorkspaceCommandError::blocking_worker_failed())?
}
