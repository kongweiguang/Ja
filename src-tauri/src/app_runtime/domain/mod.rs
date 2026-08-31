// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime Host 纯领域模型；这里不持有文件、进程、Tauri 或动态 JSON 能力。

pub(crate) mod commands;
pub(crate) mod identity;
pub(crate) mod projections;
pub(crate) mod recovery;
pub(crate) mod runtime;

pub(crate) use identity::{valid_frozen_turn_id, valid_protocol_id, valid_text_id};
pub use projections::{AttachmentMetadata, GeneralWorkspace, RuntimeStorageInfo, WorkspaceDto};
pub use recovery::{ManualRecoveryConfirmation, ManualRecoveryReason, RuntimeRecoveryState};
pub use runtime::{RuntimeStatus, RuntimeStatusKind};

pub use commands::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput,
    RuntimeConfigurationStatus, ToolArtifactReadInput, ToolArtifactReadResult, TurnAccepted,
    TurnCancelInput, TurnCancelResult, TurnChangeSetReadInput, TurnChangeSetReadResult,
    TurnContentPart, TurnQueuedInput, TurnQueuedInputResult, TurnStartInput, WorkspaceOpenInput,
};
