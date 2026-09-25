// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::EntryKind;
use crate::workspace::WorkspaceError;

/// 外部打开目标是固定闭集，WebView 不能提供 executable 或 argv。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpenWithTarget {
    Vscode,
    VisualStudio,
    Zed,
    FileExplorer,
    Terminal,
    GitBash,
    Wsl,
    Pycharm,
    Webstorm,
}

impl OpenWithTarget {
    /// 固定顺序同时服务可用性查询和 UI，避免两个列表随版本漂移。
    pub(crate) const ALL: [Self; 9] = [
        Self::Vscode,
        Self::VisualStudio,
        Self::Zed,
        Self::FileExplorer,
        Self::Terminal,
        Self::GitBash,
        Self::Wsl,
        Self::Pycharm,
        Self::Webstorm,
    ];

    /// 只解析公开 wire 名称，未知值在任何文件系统或进程调用前失败。
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "vscode" => Some(Self::Vscode),
            "visual_studio" => Some(Self::VisualStudio),
            "zed" => Some(Self::Zed),
            "file_explorer" => Some(Self::FileExplorer),
            "terminal" => Some(Self::Terminal),
            "git_bash" => Some(Self::GitBash),
            "wsl" => Some(Self::Wsl),
            "pycharm" => Some(Self::Pycharm),
            "webstorm" => Some(Self::Webstorm),
            _ => None,
        }
    }

    /// 产品名称由领域闭集给出，基础设施的候选路径永不参与展示。
    pub(crate) const fn display_name(self) -> &'static str {
        match self {
            Self::Vscode => "VS Code",
            Self::VisualStudio => "Visual Studio",
            Self::Zed => "Zed",
            Self::FileExplorer => "文件资源管理器",
            Self::Terminal => "终端",
            Self::GitBash => "Git Bash",
            Self::Wsl => "WSL",
            Self::Pycharm => "PyCharm",
            Self::Webstorm => "WebStorm",
        }
    }
}

/// 不可用原因保持稳定且不含安装路径或平台诊断。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpenTargetUnavailableReason {
    NotInstalled,
    UnsupportedPlatform,
}

/// 单个目标的可用性快照不携带解析得到的 executable path。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct OpenTargetAvailability {
    pub target: OpenWithTarget,
    pub available: bool,
    pub reason: Option<OpenTargetUnavailableReason>,
}

/// 打开成功只返回受控目标、相对路径和真实节点类型。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct OpenResult {
    pub target: OpenWithTarget,
    pub relative_path: String,
    pub entry_kind: EntryKind,
}

/// Open error 区分路径策略、不可打开节点、目标发现和进程启动，供 interface 稳定映射。
#[derive(Debug)]
pub(crate) enum OpenError {
    Workspace(WorkspaceError),
    InvalidInput,
    NotOpenable,
    TargetUnavailable,
    LaunchFailed,
}

impl From<WorkspaceError> for OpenError {
    /// 保留 Workspace error 供 interface 统一脱敏映射，不在 infrastructure 绑定 IPC code。
    fn from(error: WorkspaceError) -> Self {
        Self::Workspace(error)
    }
}
