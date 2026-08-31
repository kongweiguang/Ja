// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 单 generation client、session、pending 与 wire actor。
//!
//! 请求句柄和 stdio 并发资源共同绑定一个 generation，因此放在同一内部模块；
//! pending registry 与 wire pump 不对 façade 暴露，避免出现第二个生命周期 owner。

pub(crate) mod pending;
pub(crate) mod session;

use pending::{PendingRegistry, ResolveDisposition, deadline_after};
pub use session::{EventPump, Session, SessionEvent};
pub(crate) use session::{TerminalCallback, TerminalReason};
