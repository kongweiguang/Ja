// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 最小 HTTP(S) Preview 模型与 Tauri 子 WebView command。

pub(crate) mod commands;
pub(crate) mod error;
pub(crate) mod load_watchdog;
pub(crate) mod model;
pub(crate) mod policy;
pub(crate) mod session;

pub use commands::{
    PREVIEW_EVENT, PreviewCommandHost, PreviewEventsInput, PreviewLayoutInput,
    PreviewNavigateInput, PreviewRecoveryReport, PreviewSessionInput, PreviewUrlInput,
    PreviewViewportInput, ja_preview_close, ja_preview_events, ja_preview_layout,
    ja_preview_navigate, ja_preview_open, ja_preview_recover_pending, ja_preview_state,
};
pub use error::{PreviewError, PreviewErrorCode};
pub use model::{
    NavigationSource, PreviewEvent, PreviewEventKind, PreviewGeneration, PreviewId, PreviewLimits,
    PreviewLoadStatus, PreviewNavigationRequest, PreviewOpenResult, PreviewSessionSnapshot,
    PreviewSessionStatus, PreviewShutdownReport, PreviewUrl, PreviewWindowSpec,
};
pub use policy::{PreviewNavigationDecision, PreviewPolicy};
pub use session::PreviewManager;

// 纯规则测试从 crate tests/unit 接入，生产目录只保留模块边界，不承载测试实现。
