// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 用例层。
//
// `ReviewService` 是 catalog、snapshot、lazy diff、apply 与 cancel 的唯一用例入口；
// 原生 Git、临时 index 和 worktree 恢复只能通过本层定义的窄端口接入。

pub(crate) mod error;
pub(crate) mod operation;
pub(crate) mod ports;
pub(crate) mod service;

pub use error::{ReviewError, ReviewErrorCode};
pub(crate) use operation::ReviewOperation;
pub(crate) use ports::ReviewNativePort;
pub(crate) use service::ReviewService;
