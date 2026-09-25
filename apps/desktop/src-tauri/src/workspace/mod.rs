// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 桌面工作台使用的 Workspace 权威原生能力。
//
// 注册表与服务层把路径策略、有界读取、CAS 写入、原生回收站、Drop 准入和
// Watcher 状态封装在不透明 Workspace id 之后；Tauri command adapter 只暴露
// 类型化且经过路径脱敏的边界。

pub(crate) mod application;
pub(crate) mod domain;
pub(crate) mod infrastructure;
pub mod interface;

pub use application::TreePageRequest;
pub use domain::{
    ContentKind, EntryKind, FileContent, FileMetadata, FileRevision, LineEnding, SearchHit,
    TextEncoding, TreeEntry, TreePage,
};
pub use domain::{IoFailureKind, WorkspaceError};
pub use domain::{OpenTargetUnavailableReason, OpenWithTarget};
pub(crate) use infrastructure::consume_native_drop;
pub(crate) use infrastructure::issue_native_drop;
pub use infrastructure::{
    ChangeBatch, ChangeDetector, ChangeKind, ChangeRecord, PollState, PollingChangeDetector,
    PollingPolicy,
};
pub use infrastructure::{ContentPolicy, FileReader};
pub(crate) use infrastructure::{
    MutationInfrastructureError, PathMutationQueue, ResolvedPath, is_reparse_point, path_is_within,
    reject_link_components, replace_atomically, resolve_relative,
};
pub use infrastructure::{SearchPolicy, TextSearch, TextSearchResult};
pub use infrastructure::{TreePolicy, TreeReader};
pub use infrastructure::{WorkspaceHandle, WorkspaceId, WorkspaceInfo, WorkspaceRegistry};
pub(crate) use infrastructure::{is_shutdown_complete, shutdown_all_until};
pub use interface::{
    WorkspaceChangedEventDto, WorkspaceWatchRescanInput, WorkspaceWatchRescanResultDto,
    WorkspaceWatchStartInput, WorkspaceWatchStartResultDto, WorkspaceWatchStopInput,
    WorkspaceWatchStopResultDto, ja_workspace_watch_rescan, ja_workspace_watch_start,
    ja_workspace_watch_stop,
};
pub use interface::{
    WorkspaceCommandError, WorkspaceCommandErrorCode, WorkspaceFileContentDto,
    WorkspaceFileMetadataDto, WorkspaceFileRevisionDto, WorkspaceReadFileInput,
    WorkspaceSearchHitDto, WorkspaceSearchInput, WorkspaceSearchResultDto, WorkspaceTreeEntryDto,
    WorkspaceTreeInput, WorkspaceTreePageDto, ja_workspace_read_file, ja_workspace_search,
    ja_workspace_tree,
};
pub use interface::{
    WorkspaceCreateEntryInput, WorkspaceCreateEntryKind, WorkspaceCreateEntryResultDto,
    WorkspaceDropImportInput, WorkspaceDropImportResultDto, WorkspaceFileRevisionInput,
    WorkspaceFileSaveInput, WorkspaceFileSaveResultDto, WorkspaceMoveEntryInput,
    WorkspaceMoveEntryResultDto, WorkspaceTextContentInput, WorkspaceTrashCommitInput,
    WorkspaceTrashCommitResultDto, WorkspaceTrashPrepareInput, WorkspaceTrashPrepareResultDto,
    ja_workspace_create_entry, ja_workspace_import_drop, ja_workspace_move_entry,
    ja_workspace_save_file, ja_workspace_trash_commit, ja_workspace_trash_prepare,
};
pub use interface::{
    WorkspaceOpenCommandError, WorkspaceOpenCommandErrorCode, WorkspaceOpenInput,
    WorkspaceOpenResultDto, WorkspaceOpenTargetDto, WorkspaceOpenTargetsDto,
    WorkspaceOpenTargetsInput, ja_workspace_open, ja_workspace_open_targets,
};
