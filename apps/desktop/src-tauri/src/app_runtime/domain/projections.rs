// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime application 使用的稳定投影；字段闭集不携带 Tauri、文件或进程实现。

/// Workspace 类型由 Java 持久化签发，Rust 仅用闭集把原生能力绑定到正确目录。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkspaceKind {
    Project,
    Session,
}

impl WorkspaceKind {
    /// JA-RPC 只允许真实项目与临时会话，未知或旧类型必须失败关闭。
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "project" => Some(Self::Project),
            "session" => Some(Self::Session),
            _ => None,
        }
    }

    /// IPC 投影使用与 Java 一致的稳定枚举值，不对外暴露 Rust 类型名。
    pub const fn wire_name(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Session => "session",
        }
    }
}

/// Java persistence 返回的 Workspace 投影；application 用它提交 capability binding。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceDto {
    pub workspace_id: String,
    pub root: String,
    pub display_name: String,
    pub trust: String,
    pub revision: u64,
    pub kind: WorkspaceKind,
}

/// Thread 切换时返回已校验的 Java root，Renderer 可以展示它但不能将其作为 native 输入。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceActivation {
    pub workspace_id: String,
    pub root_path: String,
    pub display_name: String,
    pub trust: String,
    pub kind: WorkspaceKind,
}

/// Settings 页面只需要脱敏目录事实；启动参数与环境始终留在 infrastructure。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeStorageInfo {
    pub native_image: bool,
    pub data_path: String,
    pub log_path: Option<String>,
    pub cache_path: Option<String>,
    pub last_backup: Option<String>,
    pub storage_recovery: Option<StorageRecovery>,
}

/// 恢复决策由 Java 负责；宿主仅携带稳定事实和不透明的备份身份。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StorageRecovery {
    pub id: String,
    pub phase: String,
    pub reason: Option<String>,
    pub history_available: bool,
    pub backup_available: bool,
    pub rebuilt: bool,
}

/// Java 受管附件的最小原生投影；hash、workspace、时间与物理路径不会继续进入 WebView。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentMetadata {
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: Option<String>,
    pub state: String,
}
