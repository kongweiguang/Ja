// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Tauri interface 与 composition 适配层；这里只做 DTO 交接、调用用例和错误透传。

pub(crate) mod app_tray;
pub(crate) mod configuration;
pub(crate) mod desktop_preferences;
pub(crate) mod event_projection;
pub(crate) mod goal;
pub(crate) mod history;
pub(crate) mod history_model;
pub(crate) mod runtime;
pub(crate) mod settings;
pub(crate) mod settings_model;
pub(crate) mod task;

pub use configuration::*;
pub use dto::{
    RuntimeRecoveryStateDto, RuntimeStatusDto, ToolArtifactReadInputDto, ToolArtifactReadResultDto,
    TurnAcceptedDto, TurnCancelResultDto, TurnChangeSetReadInputDto, TurnChangeSetReadResultDto,
    WorkspacePathSearchInputDto, WorkspacePathSearchResultDto,
};
pub use event_projection::RPC_FRAME_EVENT;
pub use goal::*;
pub use history::*;
pub use history_model::WorkspaceWireDto;
pub use runtime::*;
pub use settings::*;
pub use task::*;
pub(crate) mod dto;
