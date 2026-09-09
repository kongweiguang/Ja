<!-- @author kongweiguang -->

# README 截图来源

这两张图用于仓库 README，来自当前桌面端生产 React 组件和全局样式，不包含用户数据，也不调用 Provider。

- `conversation.png`：`AppTitlebar`、`NavigationSidebar`、`ChatTimeline`、`WorkProcess`、`Composer`，使用内存中的网站改版示例。
- `review.png`：现有 `ReviewPanelView`、`ReviewShell`、文件树和 Diff，使用两个网站示例文件：`src/pages/Home.tsx`、`src/styles/home.css`。

## 重新生成

在仓库根目录执行：

```powershell
pnpm exec vite --config apps/desktop/vite.config.ts --port 1459
```

另开 PowerShell 执行截图脚本：

```powershell
node scripts/docs/capture-readme.mjs
```

脚本使用 Playwright 访问以下示例入口：

```text
http://127.0.0.1:1459/tests/app/e2e/readmeBrowserFixture.html
http://127.0.0.1:1459/tests/app/e2e/reviewRedesignBrowserFixture.html?theme=light&demo=readme
```

截图脚本需等待页面稳定；对话页需点击生产 `WorkProcess` 的折叠标题后截图。当前对话截图尺寸为 `1280x760`，Review 截图裁剪为 `1280x500` 以聚焦文件与 Diff，仅证明浏览器渲染边界，不证明 Windows Tauri/WebView2 原生窗口行为。
