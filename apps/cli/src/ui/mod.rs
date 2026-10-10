// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 终端展示层只消费 controller 投影的状态，不直接访问协议或持久化事实。

mod bridge;
mod composer;
mod markdown;
mod markdown_stream;
mod model;
mod model_settings;
mod native_backend;
mod render;
mod state;
mod transcript;
mod usage;

pub use bridge::{EventDelivery, TuiBridge, TuiError, UiEventSender};
pub use composer::Composer;
pub use model::{
    InteractionAnswer, InteractionQuestion, InteractionQuestionKind, PendingPrompt,
    StreamPresentation, TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiAction, UiChoice,
    UiCommand, UiEvent, UiReference, UiReferenceKind, UiSnapshot, UiSubmitMode, UiUsageSnapshot,
};
pub use model_settings::{
    ModelSettingsCredential, ModelSettingsOutcome, ModelSettingsRequest, ModelSettingsResult,
};
pub use render::render;
pub use state::UiState;
