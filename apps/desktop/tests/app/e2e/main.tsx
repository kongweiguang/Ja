// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import React from "react";
import ReactDOM from "react-dom/client";
import App from "@/app/App";
import { createE2eProjectPicker } from "./projectPicker";

const projectPicker = createE2eProjectPicker({
  VITE_JA_E2E_PROJECT_PATH: import.meta.env["VITE_JA_E2E_PROJECT_PATH"],
});

// E2E composition 保留与生产入口相同的 StrictMode，只替换明确的项目选择端口；
// 这样测试仍验证真实 Provider、controller、Tauri adapter 与 WebView 生命周期。
ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App projectPicker={projectPicker} />
  </React.StrictMode>,
);
