// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace 原生 capability 的路径准入适配。

use super::path_policy::is_reparse_point;
use crate::app_runtime::{RuntimeCommandError, WorkspaceOpenInput};
use std::fs;
use std::path::PathBuf;

/// 仅记录原生侧选择的 workspace；Java 必须从自己的 home/data 存储解析配置、profile 与凭据。
#[derive(Debug, Clone)]
pub struct RuntimeConfigSource {
    pub(crate) root_path: PathBuf,
    pub(crate) display_name: Option<String>,
    pub(crate) trust: String,
}
impl RuntimeConfigSource {
    /// IPC 边界只校验受信任的 workspace selector；完整 config revision 仍需再次与 Rust 的 TOML owner 核对。
    pub(crate) fn from_input(input: WorkspaceOpenInput) -> Result<Self, RuntimeCommandError> {
        if !matches!(input.trust.as_str(), "untrusted" | "trusted")
            || input.cwd.is_empty()
            || input.cwd.len() > 4096
            || input.cwd.chars().any(|character| character.is_control())
            || input.display_name.as_ref().is_some_and(|value| {
                value.is_empty()
                    || value.len() > 256
                    || value.chars().any(|character| character.is_control())
            })
        {
            return Err(RuntimeCommandError::invalid_params());
        }
        let raw_root = PathBuf::from(&input.cwd);
        let metadata =
            fs::symlink_metadata(&raw_root).map_err(|_| RuntimeCommandError::configuration())?;
        if metadata.file_type().is_symlink() || is_reparse_point(&metadata) || !metadata.is_dir() {
            return Err(RuntimeCommandError::configuration());
        }
        let root_path =
            fs::canonicalize(&raw_root).map_err(|_| RuntimeCommandError::configuration())?;
        Ok(Self {
            root_path,
            display_name: input.display_name,
            trust: input.trust,
        })
    }
}
