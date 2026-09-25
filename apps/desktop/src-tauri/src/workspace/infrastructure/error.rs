// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::IoFailureKind;

impl From<std::io::Error> for IoFailureKind {
    /// 平台错误在 infrastructure 边界收敛为领域闭集，未知 kind 失败关闭为 `Other`。
    fn from(error: std::io::Error) -> Self {
        error.kind().into()
    }
}

impl From<std::io::ErrorKind> for IoFailureKind {
    /// 只映射调用方恢复策略真正区分的类别，不传播平台消息或非穷举实现细节。
    fn from(kind: std::io::ErrorKind) -> Self {
        match kind {
            std::io::ErrorKind::NotFound => Self::NotFound,
            std::io::ErrorKind::PermissionDenied => Self::PermissionDenied,
            std::io::ErrorKind::AlreadyExists => Self::AlreadyExists,
            std::io::ErrorKind::WouldBlock => Self::WouldBlock,
            std::io::ErrorKind::InvalidInput => Self::InvalidInput,
            std::io::ErrorKind::InvalidData => Self::InvalidData,
            std::io::ErrorKind::TimedOut => Self::TimedOut,
            std::io::ErrorKind::Interrupted => Self::Interrupted,
            std::io::ErrorKind::Unsupported => Self::Unsupported,
            _ => Self::Other,
        }
    }
}
