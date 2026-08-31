// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import React from "react";
import ReactDOM from "react-dom/client";
import App from "./app/App";

// StrictMode 在开发期验证 Provider 清理与未来 sidecar 订阅的幂等性，
// 同时不改变生产 composition root。
ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
