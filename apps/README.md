<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Apps

可运行的用户界面应用放在本目录。

- `desktop/`：完整桌面应用，`src/` 为 React/Vite 前端，`tests/` 为前端测试，
  `src-tauri/` 为原生宿主及其 Rust 测试、Capability 和资源。
- `cli/`：Rust CLI/TUI 应用，包含终端入口、控制器、界面、测试和第三方许可。

从仓库根运行 `pnpm tauri dev` 或 `pnpm tauri build`；根入口明确定位桌面宿主，
构建钩子回到仓库根运行前端命令。前端与原生壳通过 typed wrapper 连接，
不允许组件直接散落 raw `invoke` 或 `listen`。

两个应用复用 `crates/ja-runtime`，业务状态仍由根目录 `app-server` 唯一持有。
CLI 的 npm 分发包装位于 `packages/ja-npm`，不承担终端业务实现。
