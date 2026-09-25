// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// workspace/cwd canonicalization 与 containment。
//
// 该边界刻意只依赖成熟 filesystem API；terminal policy 通过拒绝逃逸后的 canonical cwd
// 保持安全，不另造 identity protocol 或平台 handle proof。

use super::super::error::{TerminalError, TerminalErrorCode, map_io};
use std::path::{Path, PathBuf};

#[cfg(windows)]
const WINDOWS_NON_EXTENDED_MAX_PATH: usize = 260;

/// canonicalize 前按 host 进程目录解析相对 root，避免 shell 与 policy 使用不同基准。
pub(super) fn absolute_path(path: &Path) -> Result<PathBuf, std::io::Error> {
    if path.is_absolute() {
        Ok(path.to_path_buf())
    } else {
        Ok(std::env::current_dir()?.join(path))
    }
}

/// canonicalize 已存在目录，让 PTY 只接收稳定 cwd，并拒绝普通文件。
pub(super) fn canonical_directory(path: &Path) -> Result<PathBuf, TerminalError> {
    let canonical = std::fs::canonicalize(path)
        .map_err(|error| map_io(TerminalErrorCode::InvalidCwd, &error))?;
    let metadata = std::fs::metadata(&canonical)
        .map_err(|error| map_io(TerminalErrorCode::InvalidCwd, &error))?;
    if metadata.is_dir() {
        Ok(canonical)
    } else {
        Err(TerminalError::new(TerminalErrorCode::InvalidCwd))
    }
}

/// 将已验证 canonical 目录转换为交互 shell 友好的拼写；Windows canonicalization 会添加
/// `\\?\` 前缀，长路径或非 Unicode 路径必须保留以维持正确性，普通路径则移除，避免
/// PowerShell prompt 暴露仅供实现使用的 provider 前缀。
pub(crate) fn interactive_shell_directory(canonical: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        let Some(raw) = canonical.to_str() else {
            return canonical;
        };
        if raw.encode_utf16().count() >= WINDOWS_NON_EXTENDED_MAX_PATH {
            return canonical;
        }
        if let Some(rest) = raw.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{rest}"));
        }
        if let Some(rest) = raw.strip_prefix(r"\\?\") {
            return PathBuf::from(rest);
        }
    }
    canonical
}

/// 按 path component 而非原始字符串比较，防止同名前缀的 sibling 目录被误判为 workspace 子目录。
pub(super) fn path_is_within(root: &Path, candidate: &Path) -> bool {
    let root_components = root.components().collect::<Vec<_>>();
    let candidate_components = candidate.components().collect::<Vec<_>>();
    candidate_components.len() >= root_components.len()
        && root_components
            .iter()
            .zip(candidate_components.iter())
            .all(|(left, right)| {
                #[cfg(windows)]
                {
                    left.as_os_str()
                        .to_string_lossy()
                        .eq_ignore_ascii_case(&right.as_os_str().to_string_lossy())
                }
                #[cfg(not(windows))]
                {
                    left == right
                }
            })
}
