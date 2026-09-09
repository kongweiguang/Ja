// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 附件预览在 App Server、Tauri protocol 与 WebView 之间的最小模型。

use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use std::time::Duration;
use uuid::Uuid;

pub const ATTACHMENT_PREVIEW_SCHEME: &str = "ja-attachment";
pub const ATTACHMENT_PREVIEW_HOST: &str = "localhost";
pub const ATTACHMENT_PREVIEW_MAIN_WINDOW: &str = "main";
pub const ATTACHMENT_PREVIEW_THUMBNAIL_EDGE: u32 = 320;
pub const ATTACHMENT_PREVIEW_LONG_EDGE: u32 = 4096;
pub const ATTACHMENT_PREVIEW_MAX_PIXELS: u64 = 40_000_000;
pub const ATTACHMENT_PREVIEW_MAX_DECODE_BYTES: u64 = 256 * 1024 * 1024;
pub const ATTACHMENT_PREVIEW_CACHE_BYTES: usize = 256 * 1024 * 1024;
pub const ATTACHMENT_PREVIEW_TEXT_BYTES: u64 = 1024 * 1024;
pub const ATTACHMENT_PREVIEW_READ_BYTES: u32 = 64 * 1024;

/// 资源 token 由 Rust 随机签发；没有字符串构造入口可供 renderer 注入路径或 server session。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AttachmentResourceToken(Uuid);

impl AttachmentResourceToken {
    /// 每次打开生成全新 identity，关闭后不可通过旧 URL 恢复授权。
    pub(crate) fn generate() -> Self {
        Self(Uuid::new_v4())
    }

    /// protocol parser 只接受 canonical UUID simple 形式，拒绝路径片段与宽松别名。
    pub(crate) fn parse(value: &str) -> Option<Self> {
        let parsed = Uuid::parse_str(value).ok()?;
        (parsed.simple().to_string() == value).then_some(Self(parsed))
    }
}

impl Display for AttachmentResourceToken {
    /// URL 仅使用无分隔符的 canonical UUID，减少解析别名与路径歧义。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        self.0.simple().fmt(formatter)
    }
}

/// App Server 的 opaque session 只保存在 native host 内，永远不进入资源 URL。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct AttachmentPreviewSessionId(String);

impl AttachmentPreviewSessionId {
    /// 接受 runtime 已完成 schema 校验的 identity，并再次限制长度与可打印字符防止日志/内存滥用。
    pub fn try_new(value: String) -> Option<Self> {
        (!value.is_empty()
            && value.len() <= 128
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-')))
        .then_some(Self(value))
    }

    /// 只有固定 runtime adapter 可读取原 session；protocol 响应和 renderer DTO 不使用此值。
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// 当前产品只为图片和 UTF-8 文本提供真实预览入口。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AttachmentPreviewKind {
    Image,
    Text,
}

/// App Server open 的安全投影；不含 path、hash、blob key 或内容。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentPreviewDescriptor {
    pub preview_session_id: AttachmentPreviewSessionId,
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: String,
    pub preview_kind: AttachmentPreviewKind,
}

/// Rust command open 只返回前端展示元数据和完整的受控协议 URL。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewOpenResult {
    pub preview_session_id: String,
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: String,
    pub preview_kind: AttachmentPreviewKind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resource_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thumbnail_url: Option<String>,
}

/// 文本 read 由 Tauri 解码 server Base64 后投影为 UTF-8，React 不承载二进制通道。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentTextReadResult {
    pub preview_session_id: String,
    pub offset_bytes: u64,
    pub next_offset_bytes: u64,
    pub end_of_file: bool,
    pub truncated: bool,
    pub content: String,
}

/// close 始终幂等，便于 React unmount、Preview Tab 关闭与应用退出复用同一路径。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewCloseResult {
    pub closed: bool,
}

/// protocol 资源闭集防止任意 path 映射为文件读取。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AttachmentResourceVariant {
    Thumbnail,
    Preview,
}

impl AttachmentResourceVariant {
    /// URL 只允许两个固定首段，不接受扩展名、目录遍历或 query 驱动的变体。
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "thumbnail" => Some(Self::Thumbnail),
            "preview" => Some(Self::Preview),
            _ => None,
        }
    }

    /// variant 到路径段是一一固定映射，调用方不能拼接任意资源名称。
    pub(crate) const fn as_path(self) -> &'static str {
        match self {
            Self::Thumbnail => "thumbnail",
            Self::Preview => "preview",
        }
    }
}

/// 可测试时钟避免 token TTL 测试依赖 sleep 或真实 wall clock。
pub trait AttachmentPreviewClock: Send + Sync {
    /// 返回 host 内部同一单调时间域的时刻，禁止实现混入可回拨的 wall clock。
    fn now(&self) -> Duration;
}

/// 生产使用单调时钟，系统时间调整不会意外延长或缩短预览授权。
pub struct MonotonicAttachmentPreviewClock(std::time::Instant);

impl Default for MonotonicAttachmentPreviewClock {
    /// 每个 host 独立持有起点，使 TTL 只比较同一单调时间域内的 Duration。
    fn default() -> Self {
        Self(std::time::Instant::now())
    }
}

impl AttachmentPreviewClock for MonotonicAttachmentPreviewClock {
    /// elapsed 不受系统时钟回拨影响，适合短期 bearer 授权过期判断。
    fn now(&self) -> Duration {
        self.0.elapsed()
    }
}

/// 宿主限制集中定义，生产固定 5 分钟 TTL、32 session 和 256 MiB 衍生物缓存。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AttachmentPreviewLimits {
    pub idle_ttl: Duration,
    pub max_sessions: usize,
    pub max_cache_bytes: usize,
}

impl Default for AttachmentPreviewLimits {
    /// 默认值固定对应产品契约；测试通过显式 limits 注入更小预算而不改变生产常量。
    fn default() -> Self {
        Self {
            idle_ttl: Duration::from_secs(5 * 60),
            max_sessions: 32,
            max_cache_bytes: ATTACHMENT_PREVIEW_CACHE_BYTES,
        }
    }
}
