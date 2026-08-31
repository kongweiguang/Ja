// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ErrorBoundary, type FallbackProps } from "react-error-boundary";
import { useState, type ReactElement, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import { Button } from "@/shared/ui/primitives";
import { RuntimeProvider, type RuntimeProviderProps } from "./RuntimeProvider";
import { ThemeProvider } from "./ThemeProvider";
import { recordUiDiagnostic } from "@/api/tauri/diagnostics";
import { DEFAULT_RUNTIME_HOST_PORT } from "./composition/runtimeHostAdapter";
import { DEFAULT_RUNTIME_PROJECTION_PORT } from "./composition/runtimeProjectionAdapter";

/**
 * fallback 刻意保持精简且可操作；单个视图损坏不能阻止用户重启 sidecar 或返回安全状态。
 */
function AppErrorFallback({ resetErrorBoundary }: FallbackProps): ReactElement {
  return (
    <section role="alert" aria-live="assertive">
      <h1>Ja 无法显示此页面</h1>
      <p>界面遇到未预期错误。可以重试当前视图。</p>
      <Button type="button" variant="secondary" onClick={resetErrorBoundary}>
        重试
      </Button>
    </section>
  );
}

export interface AppProvidersProps extends Omit<RuntimeProviderProps, "runtime" | "projection"> {
  children: ReactNode;
  /** 测试与嵌入宿主可替换窄端口；生产默认值只在 composition 创建。 */
  readonly runtime?: RuntimeProviderProps["runtime"];
  /** projection 测试替身只替换 Conversation owner seam，不允许 Provider 创建第二个 Store。 */
  readonly runtimeProjection?: RuntimeProviderProps["projection"];
}

/** Portal feedback 与 Shell 复用唯一 theme authority，避免引入第二个 ThemeProvider。 */
function JaToaster(): ReactElement {
  const theme = useUiPreferencesStore((state) => state.themeMode);
  return (
    <Toaster
      className="ja-toaster"
      theme={theme}
      position="bottom-right"
      visibleToasts={4}
      closeButton
    />
  );
}

/**
 * 只报告封闭 fault code；主动丢弃 Error object 与 component stack，防止 Prompt、路径和
 * 已渲染内容泄漏。
 */
function reportReactError(): void {
  void recordUiDiagnostic("react_error_boundary");
}

/**
 * composition root 与生成的 Tauri entrypoint 分离，使 host integration 可选择 fake 或
 * real transport，而不让 UI 与传输实现耦合。
 */
export function AppProviders({
  children,
  runtime,
  runtimeProjection,
}: AppProvidersProps): ReactElement {
  const runtimeHost = runtime ?? DEFAULT_RUNTIME_HOST_PORT;
  const projection = runtimeProjection ?? DEFAULT_RUNTIME_PROJECTION_PORT;
  /**
   * 每个桌面 composition root 独占 QueryClient；native invoke 无 AbortSignal，因此缓存键负责
   * 隔离 workspace/generation，禁止旧结果覆盖新范围，但不声称终止已发出的原生调用。
   */
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: Number.POSITIVE_INFINITY,
            gcTime: 5 * 60 * 1_000,
            retry: false,
            refetchOnWindowFocus: false,
            refetchOnReconnect: false,
            refetchOnMount: false,
          },
          mutations: { retry: false },
        },
      }),
  );
  return (
    <ErrorBoundary FallbackComponent={AppErrorFallback} onError={reportReactError}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>
          <RuntimeProvider runtime={runtimeHost} projection={projection}>
            {children}
          </RuntimeProvider>
          <JaToaster />
        </ThemeProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
