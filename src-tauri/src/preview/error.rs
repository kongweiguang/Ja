// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 稳定 Preview 错误，永不回显 URL 或 WebView 诊断。

use serde::Serialize;
use std::fmt::{Display, Formatter};

/// IPC 与测试共用的 UI-safe Preview 故障类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[repr(u16)]
pub enum PreviewErrorCode {
    InvalidConfig = 1,
    UrlTooLong = 2,
    UrlControlCharacter = 3,
    UrlInvalid = 4,
    SchemeNotAllowed = 5,
    HostMissing = 6,
    UserInfoNotAllowed = 7,
    PercentEscapeInvalid = 8,
    SessionLimit = 9,
    SessionNotFound = 10,
    SessionClosed = 11,
    StaleGeneration = 12,
    GenerationExhausted = 13,
    SequenceExhausted = 14,
    EventPayloadTooLarge = 15,
    EventQueueFull = 16,
    TitleTooLong = 17,
    ErrorTooLong = 18,
    NavigationBlocked = 19,
    DependencyRequest = 20,
    InternalStateUnavailable = 21,
    ViewportInvalid = 22,
    ShutdownStarted = 23,
    SessionClosing = 24,
    ShutdownDeadline = 25,
    NativeClosePending = 26,
}

/// Error 只携带稳定类别，不携带调用方输入。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct PreviewError {
    pub code: PreviewErrorCode,
}

impl PreviewError {
    /// 构造适合 Tauri command result 的脱敏错误。
    pub const fn new(code: PreviewErrorCode) -> Self {
        Self { code }
    }

    /// 返回聚焦 unit test 使用的类别，不公开原生诊断。
    pub const fn code(self) -> PreviewErrorCode {
        self.code
    }
}

impl Display for PreviewError {
    /// 只将稳定类别映射为静态文本，使原生诊断永不穿过 IPC。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code {
            PreviewErrorCode::InvalidConfig => "preview configuration is invalid",
            PreviewErrorCode::UrlTooLong => "preview URL is too long",
            PreviewErrorCode::UrlControlCharacter => "preview URL contains a control character",
            PreviewErrorCode::UrlInvalid => "preview URL is invalid",
            PreviewErrorCode::SchemeNotAllowed => "preview URL scheme is not allowed",
            PreviewErrorCode::HostMissing => "preview URL host is missing",
            PreviewErrorCode::UserInfoNotAllowed => "preview URL user info is not allowed",
            PreviewErrorCode::PercentEscapeInvalid => "preview URL percent escape is invalid",
            PreviewErrorCode::SessionLimit => "preview session limit reached",
            PreviewErrorCode::SessionNotFound => "preview session was not found",
            PreviewErrorCode::SessionClosed => "preview session is closed",
            PreviewErrorCode::StaleGeneration => "preview callback generation is stale",
            PreviewErrorCode::GenerationExhausted => "preview generation is exhausted",
            PreviewErrorCode::SequenceExhausted => "preview event sequence is exhausted",
            PreviewErrorCode::EventPayloadTooLarge => "preview event payload is too large",
            PreviewErrorCode::EventQueueFull => "preview event queue is full",
            PreviewErrorCode::TitleTooLong => "preview title is too long",
            PreviewErrorCode::ErrorTooLong => "preview load error is too long",
            PreviewErrorCode::NavigationBlocked => "preview navigation is blocked",
            PreviewErrorCode::DependencyRequest => "preview host integration is unavailable",
            PreviewErrorCode::InternalStateUnavailable => "preview state is unavailable",
            PreviewErrorCode::ViewportInvalid => "preview viewport is invalid",
            PreviewErrorCode::ShutdownStarted => "preview host is shutting down",
            PreviewErrorCode::SessionClosing => "preview session close is already in progress",
            PreviewErrorCode::ShutdownDeadline => "preview shutdown deadline was reached",
            PreviewErrorCode::NativeClosePending => "preview native close is still pending",
        })
    }
}

impl std::error::Error for PreviewError {}
