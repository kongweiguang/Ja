// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use serde::{Deserialize, Serialize};

/// 只读 sidebar 只需要 Porcelain-v2 status record category，不引入写操作状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GitStatusKind {
    Head,
    Changed,
    Renamed,
    Unmerged,
    Untracked,
    Ignored,
}

/// 解析后的机器格式 status record 只保留 root-relative path。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GitStatusEntry {
    pub kind: GitStatusKind,
    pub index_status: Option<char>,
    pub worktree_status: Option<char>,
    pub path: String,
    pub original_path: Option<String>,
}

/// raw diff byte 保留 binary patch，避免错误猜测 UTF-8 编码。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GitDiff {
    pub bytes: Vec<u8>,
    pub truncated: bool,
}

/// timeline/Workbench view 使用的有界 NUL-delimited log 投影。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GitLogEntry {
    pub object_id: String,
    pub parents: Vec<String>,
    pub author: String,
    pub email: String,
    pub authored_at: String,
    pub subject: String,
}
