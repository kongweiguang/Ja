// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

pub(crate) mod mutation;
pub(crate) mod open_with;
pub(crate) mod query;
pub(crate) mod watch;

pub(crate) use mutation::{
    WorkspaceCreatePort, WorkspaceDropPort, WorkspaceMovePort, WorkspaceMutationService,
    WorkspaceMutationTransaction, WorkspaceSavePort, WorkspaceTrashPort,
};
pub(crate) use open_with::{WorkspaceOpenPort, WorkspaceOpenService};
pub use query::TreePageRequest;
pub(crate) use query::{WorkspaceQueryPort, WorkspaceQueryService, WorkspaceSearchResult};
pub(crate) use watch::{WorkspaceWatchPort, WorkspaceWatchService};
