<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Crates

可脱离 Tauri UI 独立编译和测试的 Rust 能力放在本目录。

- `ja-runtime/`：Ja App Server host 的 JSONL codec、握手、会话、进程树和监督器；不得依赖
  Tauri、窗口或 WebView 类型。

仓库根 `Cargo.toml` 是 Rust workspace 入口。`.cargo/config.toml` 把所有成员的构建产物
统一写到根目录 `target/`；桌面和 CLI 应用位于 `apps/`，不占用彼此的构建目录。
验证脚本仍可显式指定隔离 target 目录。

新 crate 必须对应真实复用或隔离边界，并拥有独立测试；不为目录对称创建空 crate。
