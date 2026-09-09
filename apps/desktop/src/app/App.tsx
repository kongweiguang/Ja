// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement } from "react";
import { AppProviders } from "./AppProviders";
import { JaApplication, type JaApplicationProps } from "./composition/JaApplication";

interface AppProps extends JaApplicationProps {
  readonly runtime?: Parameters<typeof AppProviders>[0]["runtime"];
}

/**
 * 桌面入口只负责 Provider 与 feature composition 注入；路由、响应式 Shell 和各领域
 * controller 在 composition/application 边界内组装，测试仍可通过公开 AppProps 注入 typed port。
 */
function App({
  runtime,
  settingsAdapter,
  projectPicker,
  historyAdapter,
  attachmentPort,
  attachmentPreviewPort,
  nativeDropPort,
  workbenchAdapters,
  desktopAdapters,
  navigationAdapters,
}: AppProps): ReactElement {
  return (
    <AppProviders runtime={runtime}>
      <JaApplication
        settingsAdapter={settingsAdapter}
        projectPicker={projectPicker}
        historyAdapter={historyAdapter}
        attachmentPort={attachmentPort}
        attachmentPreviewPort={attachmentPreviewPort}
        nativeDropPort={nativeDropPort}
        workbenchAdapters={workbenchAdapters}
        desktopAdapters={desktopAdapters}
        navigationAdapters={navigationAdapters}
      />
    </AppProviders>
  );
}

export default App;
