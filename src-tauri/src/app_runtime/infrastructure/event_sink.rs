// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 原生 Runtime 事件投递端口；JSON 只存在于 infrastructure/interface 组合边界。

use crate::app_runtime::EventEmitError;
use serde_json::Value;
use std::sync::Arc;

/// Bridge 只持有有界投递回调；Tauri window、label 与 emitter 均不进入 actor 状态。
pub type EventSink = Arc<dyn Fn(Value) -> Result<(), EventEmitError> + Send + Sync + 'static>;
