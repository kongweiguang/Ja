// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// policy、session registry 与 Tauri host 共享的小型 Preview DTO。

use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use uuid::Uuid;

/// 不透明 Preview session identity。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct PreviewId(Uuid);

impl PreviewId {
    /// 随机 id 防止页面猜测其他 Preview owner。
    pub(crate) fn new() -> Self {
        Self(Uuid::new_v4())
    }

    /// 稳定 Tauri label 不包含 URL 或文件系统数据。
    pub(crate) fn label(self) -> String {
        format!("preview_{}", self.0.simple())
    }
}

impl Display for PreviewId {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(formatter)
    }
}

/// generation 用于拒绝已完成 navigation 页面的陈旧 callback。
pub type PreviewGeneration = u64;

/// 只有 PreviewPolicy 或本地文件 resolver 校验后才构造 HTTP(S)/file/about:blank 页面 URL。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct PreviewUrl(String);

impl PreviewUrl {
    /// HTTP(S)/file 由 policy 校验后构造，唯一直接值由 Rust 固定为 about:blank。
    pub(crate) fn from_normalized(value: String) -> Self {
        Self(value)
    }

    /// 返回供 Tauri external WebView 使用的 normalized URL。
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Display for PreviewUrl {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for PreviewUrl {
    /// UI-safe snapshot/event 还会回读本地页面和空白页；反序列化只接受这三类受限 URL。
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let raw = String::deserialize(deserializer)?;
        super::policy::PreviewPolicy::new()
            .and_then(|policy| policy.validate_wire_url(&raw))
            .map_err(serde::de::Error::custom)
    }
}

/// 对 UI 可见的 Preview 生命周期闭集。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreviewSessionStatus {
    Open,
    Closed,
}

/// 将 navigation commitment 与 engine 页面加载终态事实分开。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreviewLoadStatus {
    Loading,
    Finished,
    Failed,
}

/// source 保持精简，因为 popup/browser automation 不在当前范围。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NavigationSource {
    User,
    Redirect,
}

/// 只向主 renderer 暴露被原生策略拒绝的两类浏览器动作。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreviewBlockedAction {
    Popup,
    Download,
}

/// Tauri 子窗口描述；window label 不具有主窗口 capability。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PreviewWindowSpec {
    label: String,
    url: PreviewUrl,
}

impl PreviewWindowSpec {
    /// 创建主窗口 capability scope 中不存在的 label。
    pub(crate) fn new(id: PreviewId, url: PreviewUrl) -> Self {
        Self {
            label: id.label(),
            url,
        }
    }

    /// 返回 Tauri 子窗口 label，不暴露 URL。
    pub fn label(&self) -> &str {
        &self.label
    }

    /// 返回已经过 policy 校验的 URL。
    pub fn url(&self) -> &PreviewUrl {
        &self.url
    }
}

/// 发往主 UI 的单个有界事件。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case", tag = "type")]
pub enum PreviewEventKind {
    Opened {
        url: PreviewUrl,
    },
    NavigationCommitted {
        source: NavigationSource,
        url: PreviewUrl,
    },
    TitleChanged {
        title: String,
    },
    LoadFailed {
        message: String,
    },
    LoadFinished {
        url: PreviewUrl,
    },
    HistoryChanged {
        can_go_back: bool,
        can_go_forward: bool,
    },
    ActionBlocked {
        action: PreviewBlockedAction,
    },
    Closed,
}

/// 事件 identity 支持 reload 投影与陈旧 callback 拒绝。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PreviewEvent {
    pub session_id: PreviewId,
    pub generation: PreviewGeneration,
    pub sequence: u64,
    pub kind: PreviewEventKind,
}

/// open result 只包含 UI-safe 模型状态与子窗口 spec。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PreviewOpenResult {
    pub snapshot: PreviewSessionSnapshot,
    pub window: PreviewWindowSpec,
}

/// reload 或晚订阅事件后使用的权威 snapshot。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PreviewSessionSnapshot {
    pub id: PreviewId,
    pub generation: PreviewGeneration,
    pub status: PreviewSessionStatus,
    pub load_status: PreviewLoadStatus,
    pub url: PreviewUrl,
    pub title: String,
    pub window: PreviewWindowSpec,
    pub dropped_events: u64,
    pub can_go_back: bool,
    pub can_go_forward: bool,
}

/// 脱敏 shutdown 计数证明哪些原生 close request 已得到 ACK。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct PreviewShutdownReport {
    pub shutdown_started: bool,
    pub in_flight_operations: usize,
    pub sessions_observed: usize,
    pub close_acknowledged: usize,
    pub already_absent: usize,
    pub close_failed: usize,
    pub pending_sessions: usize,
    pub deadline_exceeded: bool,
    pub complete: bool,
}

/// command 调用 `WebView.navigate` 前使用的 navigation 校验结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreviewNavigationRequest {
    pub(crate) session_id: PreviewId,
    pub(crate) generation: PreviewGeneration,
    pub(crate) source: NavigationSource,
    pub(crate) url: PreviewUrl,
}

/// 单页事件和字符串预算避免页面事件填满 host；标签数由用户按浏览器习惯管理。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PreviewLimits {
    pub max_url_bytes: usize,
    pub max_title_bytes: usize,
    pub max_error_bytes: usize,
    pub max_event_count: usize,
    pub max_event_payload_bytes: usize,
    pub max_event_queue_bytes: usize,
}

impl Default for PreviewLimits {
    /// 这些限制覆盖普通文档页面，同时不创建无界状态。
    fn default() -> Self {
        Self {
            max_url_bytes: 8 * 1024,
            max_title_bytes: 1024,
            max_error_bytes: 4 * 1024,
            max_event_count: 512,
            max_event_payload_bytes: 64 * 1024,
            max_event_queue_bytes: 2 * 1024 * 1024,
        }
    }
}

impl PreviewLimits {
    /// registry 开始接收页面前校验各预算关系。
    pub(crate) fn validate(self) -> Result<Self, super::error::PreviewError> {
        let valid = self.max_url_bytes >= 64
            && self.max_url_bytes <= 64 * 1024
            && self.max_title_bytes > 0
            && self.max_title_bytes <= 64 * 1024
            && self.max_error_bytes > 0
            && self.max_error_bytes <= 256 * 1024
            && self.max_event_count > 0
            && self.max_event_count <= 65_536
            && self.max_event_payload_bytes >= 128
            && self.max_event_payload_bytes <= 4 * 1024 * 1024
            && self.max_event_queue_bytes >= self.max_event_payload_bytes
            && self.max_event_queue_bytes <= 64 * 1024 * 1024;
        valid.then_some(self).ok_or(super::error::PreviewError::new(
            super::error::PreviewErrorCode::InvalidConfig,
        ))
    }
}

impl PreviewEvent {
    /// 按序列化字节计数，使 event queue accounting 与 IPC 实际载荷一致。
    pub(crate) fn encoded_bytes(&self) -> usize {
        serde_json::to_vec(self).map_or(usize::MAX, |bytes| bytes.len())
    }
}
