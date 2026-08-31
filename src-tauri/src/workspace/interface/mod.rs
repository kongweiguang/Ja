// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

pub mod mutation;
pub mod open_with;
pub mod query;
pub(crate) mod serde_types;
pub mod watch;

pub use mutation::{
    WorkspaceCreateEntryInput, WorkspaceCreateEntryKind, WorkspaceCreateEntryResultDto,
    WorkspaceDropImportInput, WorkspaceDropImportResultDto, WorkspaceFileRevisionInput,
    WorkspaceFileSaveInput, WorkspaceFileSaveResultDto, WorkspaceMoveEntryInput,
    WorkspaceMoveEntryResultDto, WorkspaceTextContentInput, WorkspaceTrashCommitInput,
    WorkspaceTrashCommitResultDto, WorkspaceTrashPrepareInput, WorkspaceTrashPrepareResultDto,
    ja_workspace_create_entry, ja_workspace_import_drop, ja_workspace_move_entry,
    ja_workspace_save_file, ja_workspace_trash_commit, ja_workspace_trash_prepare,
};
pub use open_with::{
    WorkspaceOpenCommandError, WorkspaceOpenCommandErrorCode, WorkspaceOpenInput,
    WorkspaceOpenResultDto, WorkspaceOpenTargetDto, WorkspaceOpenTargetsDto,
    WorkspaceOpenTargetsInput, ja_workspace_open, ja_workspace_open_targets,
};
pub use query::{
    WorkspaceCommandError, WorkspaceCommandErrorCode, WorkspaceFileContentDto,
    WorkspaceFileMetadataDto, WorkspaceFileRevisionDto, WorkspaceReadFileInput,
    WorkspaceSearchHitDto, WorkspaceSearchInput, WorkspaceSearchResultDto, WorkspaceTreeEntryDto,
    WorkspaceTreeInput, WorkspaceTreePageDto, ja_workspace_read_file, ja_workspace_search,
    ja_workspace_tree,
};
pub use watch::{
    WorkspaceChangedEventDto, WorkspaceWatchRescanInput, WorkspaceWatchRescanResultDto,
    WorkspaceWatchStartInput, WorkspaceWatchStartResultDto, WorkspaceWatchStopInput,
    WorkspaceWatchStopResultDto, ja_workspace_watch_rescan, ja_workspace_watch_start,
    ja_workspace_watch_stop,
};
