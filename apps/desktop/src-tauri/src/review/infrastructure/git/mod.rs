// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 私有 Git 适配器。
//
// 查询 façade、进程执行和仓库安全策略只服务于 Review infrastructure，避免恢复顶层
// Git owner 或重新暴露已淘汰的 Tauri command 面。

pub(crate) mod adapter;
pub(crate) mod error;
pub(crate) mod model;
pub(crate) mod parse;
pub(crate) mod process;
pub(crate) mod repository_policy;

pub(super) use adapter::{DiffOptions, GitReadOnly};
pub(super) use error::GitError;
pub(super) use model::GitStatusKind;
pub(super) use tokio_util::sync::CancellationToken;
