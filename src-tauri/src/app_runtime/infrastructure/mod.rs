// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime Host 基础设施层：实现 sidecar、恢复存储、原生路径和事件读取能力。

pub(crate) mod bridge;
pub(crate) mod event_sink;
pub(crate) mod home_layout;
pub(crate) mod launch_config;
pub(crate) mod path_policy;
pub(crate) mod platform;
pub(crate) mod process_error;
pub(crate) mod recovery_store;
pub(crate) mod workspace_capability;

pub use event_sink::EventSink;
pub(crate) use home_layout::HomeLayout;
pub use launch_config::{
    LaunchConfig, bundled_launch_config, bundled_launch_config_with_dirs, prepare_run_dir,
};
pub(crate) use platform::NativeRuntimePlatform;
pub use workspace_capability::RuntimeConfigSource;
