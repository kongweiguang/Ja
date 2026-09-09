// @author kongweiguang
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { NavigationResizeHandle } from "@/features/navigation/ui/NavigationResizeHandle";
import "@/shared/styles/tokens.css";
import "@/app/App.css";
import "@/features/navigation/ui/navigation.css";

/** 隔离验证真实拖动组件与生产圆角样式；不模拟 App Server，也不冒充完整桌面验收。 */
function WorkspaceCornerBrowserFixture() {
  const [ratio, setRatio] = useState(25);
  return (
    <div className="ja-shell">
      <div style={{ height: 36 }} />
      <div
        className="ja-layout"
        style={{ "--ja-sidebar-ratio": `${ratio}%` } as React.CSSProperties}
      >
        <div className="ja-navigation-shell">
          <div className="ja-navigation-sidebar" />
          <NavigationResizeHandle
            ratio={ratio}
            minRatio={20}
            maxRatio={45}
            onPreview={setRatio}
            onCommit={setRatio}
          />
        </div>
        <div className="ja-workspace-stage">
          <main className="ja-main" />
        </div>
      </div>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<WorkspaceCornerBrowserFixture />);
