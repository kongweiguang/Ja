// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Ja App Server sidecar 中 Agent Kernel 的 Rust host 边界。
//!
//! `app_server_process` 作为独立 crate 的稳定 namespace 暴露给 Tauri composition root；
//! 本模块自身不依赖 Tauri state，因而协议、并发和生命周期可以独立验收。

pub(crate) mod client;
pub(crate) mod error;
pub(crate) mod lifecycle;
pub(crate) mod process;
pub(crate) mod protocol;

pub use client::{EventPump, Session, SessionEvent};
pub use error::AppServerProcessError;
pub use lifecycle::{LifecycleState, SidecarSupervisor};
pub use process::SidecarConfig;
pub use protocol::{CodecError, Limits, RpcFrame, valid_ready_token};
