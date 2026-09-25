// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 终端展示层只消费 controller 投影的状态，不直接访问协议或持久化事实。

mod bridge;
mod composer;
mod markdown;
mod model;
mod render;
mod state;

pub use bridge::{EventDelivery, TuiBridge, TuiError, UiEventSender};
pub use composer::Composer;
pub use model::{
    InteractionAnswer, InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry,
    TimelineKind, TimelineStatus, TurnState, UiAction, UiChoice, UiCommand, UiEvent, UiReference,
    UiReferenceKind, UiSnapshot, UiSubmitMode,
};
pub use render::{insert_scrollback, render};
pub use state::UiState;
