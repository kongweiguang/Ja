// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

pub(crate) mod changes;
pub(crate) mod content;
pub(crate) mod error;
pub(crate) mod mutation;
pub(crate) mod open_with;
pub(crate) mod query;
pub(crate) mod registry;
pub(crate) mod search;
pub(crate) mod tree;
pub(crate) mod watch;

pub use changes::{
    ChangeBatch, ChangeDetector, ChangeKind, ChangeRecord, PollState, PollingChangeDetector,
    PollingPolicy,
};
pub use content::{ContentPolicy, FileReader};
pub(crate) use mutation::NativeWorkspaceMutationPort;
pub(crate) use mutation::{
    MutationInfrastructureError, PathMutationQueue, replace_atomically, resolve_relative,
};
pub(crate) use mutation::{consume_native_drop, issue_native_drop};
pub(crate) use open_with::NativeWorkspaceOpenPort;
pub(crate) use query::NativeWorkspaceQueryPort;
pub(crate) use registry::{ResolvedPath, is_reparse_point, path_is_within, reject_link_components};
pub use registry::{WorkspaceHandle, WorkspaceId, WorkspaceInfo, WorkspaceRegistry};
pub use search::{SearchPolicy, TextSearch, TextSearchResult};
pub use tree::{TreePolicy, TreeReader};
pub(crate) use watch::{NativeWorkspaceWatchPort, WorkspaceWatchEventSink};
pub(crate) use watch::{is_shutdown_complete, shutdown_all_until};
