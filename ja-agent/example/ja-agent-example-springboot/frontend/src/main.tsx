// @author kongweiguang
import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import "./styles.css";

/** 根组件启用严格模式，尽早发现开发时的状态结构错误，避免界面悄然偏离预期。 */
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
