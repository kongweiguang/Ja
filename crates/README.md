<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Crates

可脱离 Tauri UI 独立编译和测试的 Rust 能力放在本目录。

- `ja-runtime/`：Ja App Server host 的 JSONL codec、握手、会话、进程树和监督器；不得依赖
  Tauri、窗口或 WebView 类型。

仓库根 `Cargo.toml` 是 Rust workspace 入口。`.cargo/config.toml` 继续把共享构建产物
写到 `src-tauri/target`，保持既有 Tauri 打包和 CI 路径稳定。

新 crate 必须对应真实复用或隔离边界，并拥有独立测试；不为目录对称创建空 crate。
