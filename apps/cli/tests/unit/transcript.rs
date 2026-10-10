// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang
#![allow(dead_code)]

// 将生产 UI 模块作为本测试 crate 的顶层兄弟模块加载；这样白盒断言可见 pub(super)
// Transcript 状态，同时不在生产代码新增测试 façade 或扩大可见性。
#[path = "../../src/ui/bridge.rs"]
mod bridge;
#[path = "../../src/ui/composer.rs"]
mod composer;
#[path = "../../src/ui/markdown.rs"]
mod markdown;
#[path = "../../src/ui/markdown_stream.rs"]
mod markdown_stream;
#[path = "../../src/ui/model.rs"]
mod model;
#[path = "../../src/ui/model_settings.rs"]
mod model_settings;
#[path = "../../src/ui/native_backend.rs"]
mod native_backend;
#[path = "../../src/ui/render.rs"]
mod render;
#[path = "../../src/ui/state.rs"]
mod state;
#[path = "../../src/ui/transcript.rs"]
mod transcript;
#[path = "../../src/ui/usage.rs"]
mod usage;

#[path = "transcript_cases.rs"]
mod cases;
