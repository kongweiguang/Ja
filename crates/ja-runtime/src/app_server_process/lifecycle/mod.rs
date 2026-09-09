// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar generation 状态机与唯一 supervisor。
//!
//! 状态转换和原生进程编排位于同一生命周期边界，避免 reader、writer 或调用方
//! 各自推导终态；协议和进程细节仍由其所属内部模块负责。

pub(crate) mod machine;
pub(crate) mod supervisor;

pub use machine::LifecycleState;
pub(crate) use machine::{LifecycleMachine, RestartPolicy};
pub use supervisor::{SidecarSupervisor, TurnChangeSetReadLease};
