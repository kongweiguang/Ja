// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime 原生路径的共享 reparse 防护。

use std::fs;

/// 识别 Windows reparse point，避免 canonicalize 前的 junction/symlink 间接跳转。
/// 平台差异集中在此处，所有 Runtime 目录、资源与恢复文件共享同一判断。
#[cfg(windows)]
pub(crate) fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;

    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

/// 非 Windows 平台没有 reparse attribute；符号链接仍由调用方统一检查。
#[cfg(not(windows))]
pub(crate) fn is_reparse_point(_metadata: &fs::Metadata) -> bool {
    false
}
