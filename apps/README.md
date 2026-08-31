<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Apps

可运行的用户界面应用放在本目录。

- `desktop/`：React/Vite 桌面前端，由根 `package.json` 统一安装依赖和执行脚本。

Tauri 原生壳继续保留在仓库根 `src-tauri/`。这是现有 Tauri、打包、Capability
和 CI 工具链的稳定入口；前端与原生壳通过 typed wrapper 连接，不允许组件直接散落
raw `invoke` 或 `listen`。

只有出现第二个可独立构建、测试和交付的应用时，才在 `apps/` 下新增同级目录。
