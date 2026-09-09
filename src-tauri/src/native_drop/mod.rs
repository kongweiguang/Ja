// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 原生拖放能力只公开给 Tauri composition root，具体平台 API 留在 interface 边界。

pub(crate) mod interface;

pub(crate) use interface::{NativeDropTargetHost, schedule_main_refresh};
