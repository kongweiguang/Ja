// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar 配置、进程启动和进程树资源所有权。
//!
//! 原生 IO 与平台 ABI 收口在此模块，生命周期层只编排成功或失败事实，不能绕过
//! 配置校验、原子绑定与有界回收策略。

pub(crate) mod config;
mod spawn;
pub(crate) mod tree;

pub use config::SidecarConfig;
pub(crate) use spawn::{RunningProcess, TerminalSignal, spawn_process};
