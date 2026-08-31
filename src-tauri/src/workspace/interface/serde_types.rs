// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace 领域闭集的 wire 映射集中在 interface，domain 不依赖 serde 或 IPC 命名。

use crate::workspace::domain::{
    ContentKind, EntryKind, LineEnding, OpenTargetUnavailableReason, OpenWithTarget, TextEncoding,
};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

macro_rules! wire_enum {
    ($type:ty, {$($variant:path => $wire:literal),+ $(,)?}) => {
        impl Serialize for $type {
            /// 序列化只输出稳定 snake_case wire 值，领域类型本身不承担协议职责。
            fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
            where
                S: Serializer,
            {
                let value = match self {
                    $($variant => $wire,)+
                };
                serializer.serialize_str(value)
            }
        }

        impl<'de> Deserialize<'de> for $type {
            /// 反序列化在 interface 关闭未知值，避免 serde 规则渗入 domain 构造与状态转换。
            fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
            where
                D: Deserializer<'de>,
            {
                let value = String::deserialize(deserializer)?;
                match value.as_str() {
                    $($wire => Ok($variant),)+
                    _ => Err(serde::de::Error::unknown_variant(&value, &[$($wire),+])),
                }
            }
        }
    };
}

wire_enum!(EntryKind, {
    EntryKind::File => "file",
    EntryKind::Directory => "directory",
    EntryKind::Symlink => "symlink",
    EntryKind::ReparsePoint => "reparse_point",
    EntryKind::Other => "other",
});

wire_enum!(ContentKind, {
    ContentKind::Text => "text",
    ContentKind::Binary => "binary",
    ContentKind::UnknownEncoding => "unknown_encoding",
    ContentKind::TooLarge => "too_large",
});

wire_enum!(TextEncoding, {
    TextEncoding::Utf8 => "utf8",
    TextEncoding::Utf8Bom => "utf8_bom",
    TextEncoding::Utf16Le => "utf16_le",
    TextEncoding::Utf16Be => "utf16_be",
});

wire_enum!(LineEnding, {
    LineEnding::Lf => "lf",
    LineEnding::CrLf => "cr_lf",
    LineEnding::Cr => "cr",
    LineEnding::Mixed => "mixed",
});

wire_enum!(OpenWithTarget, {
    OpenWithTarget::Vscode => "vscode",
    OpenWithTarget::VisualStudio => "visual_studio",
    OpenWithTarget::Zed => "zed",
    OpenWithTarget::FileExplorer => "file_explorer",
    OpenWithTarget::Terminal => "terminal",
    OpenWithTarget::GitBash => "git_bash",
    OpenWithTarget::Wsl => "wsl",
    OpenWithTarget::Pycharm => "pycharm",
    OpenWithTarget::Webstorm => "webstorm",
});

wire_enum!(OpenTargetUnavailableReason, {
    OpenTargetUnavailableReason::NotInstalled => "not_installed",
    OpenTargetUnavailableReason::UnsupportedPlatform => "unsupported_platform",
});
