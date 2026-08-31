// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime application 使用的稳定投影；字段闭集不携带 Tauri、文件或进程实现。

/// 无项目会话只暴露 Java 签发 identity 与已注册的 native root 投影。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeneralWorkspace {
    pub workspace_id: String,
    pub display_name: String,
    pub trust: String,
    pub root_path: String,
}

/// Java persistence 返回的 Workspace 投影；application 用它提交 capability binding。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceDto {
    pub workspace_id: String,
    pub root: String,
    pub display_name: String,
    pub trust: String,
    pub revision: u64,
}

/// Settings 页面只需要脱敏目录事实；启动参数与环境始终留在 infrastructure。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeStorageInfo {
    pub native_image: bool,
    pub data_path: String,
    pub log_path: Option<String>,
    pub cache_path: Option<String>,
    pub last_backup: Option<String>,
}

/// Java 受管附件的最小原生投影；hash、workspace、时间与物理路径不会继续进入 WebView。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentMetadata {
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_type: Option<String>,
    pub state: String,
}
