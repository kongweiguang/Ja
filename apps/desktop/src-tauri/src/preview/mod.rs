// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 多页 Preview 模型、本地文件解析与隔离 Tauri 子 WebView command。

pub(crate) mod commands;
pub(crate) mod error;
pub(crate) mod load_watchdog;
pub(crate) mod local_file;
pub(crate) mod model;
pub(crate) mod policy;
pub(crate) mod session;

pub use commands::{
    PREVIEW_EVENT, PreviewCommandHost, PreviewEventsInput, PreviewHistoryInput, PreviewLayoutInput,
    PreviewNavigateFileInput, PreviewNavigateInput, PreviewOpenBlankInput, PreviewOpenFileInput,
    PreviewRecoveryReport, PreviewSessionInput, PreviewUrlInput, PreviewViewportInput,
    ja_preview_close, ja_preview_events, ja_preview_go_back, ja_preview_go_forward,
    ja_preview_layout, ja_preview_navigate, ja_preview_navigate_file, ja_preview_open,
    ja_preview_open_blank, ja_preview_open_file, ja_preview_recover_pending, ja_preview_reload,
    ja_preview_resolve_file, ja_preview_reveal_file, ja_preview_state,
};
pub use error::{PreviewError, PreviewErrorCode};
pub use local_file::{PreviewFileKind, PreviewFileResolution, PreviewResolveFileInput};
pub use model::{
    NavigationSource, PreviewEvent, PreviewEventKind, PreviewGeneration, PreviewId, PreviewLimits,
    PreviewLoadStatus, PreviewNavigationRequest, PreviewOpenResult, PreviewSessionSnapshot,
    PreviewSessionStatus, PreviewShutdownReport, PreviewUrl, PreviewWindowSpec,
};
pub use policy::{PreviewNavigationDecision, PreviewPolicy};
pub use session::PreviewManager;

// 纯规则测试从 crate tests/unit 接入，生产目录只保留模块边界，不承载测试实现。
