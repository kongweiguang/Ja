// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 桌面应用使用的 Ja App Server host runtime，不依赖 Tauri。
//!
//! `app_server_process` 是进程边界唯一公开 namespace，使协议合同验证无需依赖 Tauri。

pub mod app_server_process;
