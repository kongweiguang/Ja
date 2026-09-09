// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useJaWorkbench, type JaWorkbenchAdapters } from "@/app/useJaWorkbench";
import type {
  NativePreviewPort,
  PreviewSessionHintStorage,
  PreviewSessionSnapshot,
} from "@/features/workbench/preview";
import type { WorkspaceProjection } from "@/features/workspace";
import { capabilityWorkbenchTab } from "@/features/workbench";

const project: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_fixture",
  rootPath: "C:\\dev\\ja",
  displayName: "ja",
  trust: "trusted",
};

afterEach(() => cleanup());

/** composition 合同只需要一个无副作用 Preview port，其余领域端口不会被本 hook 访问。 */
function createAdapters(): JaWorkbenchAdapters {
  const snapshot: PreviewSessionSnapshot = {
    id: "00000000-0000-4000-8000-000000000002",
    generation: 0,
    status: "closed",
    load_status: "finished",
    url: "https://example.com/",
    title: "Example",
    window: { label: "ja-preview", url: "https://example.com/" },
    dropped_events: 0,
  };
  const preview: NativePreviewPort = {
    recoverPending: vi.fn(async () => ({ observed: 0, recovered: 0, failed: 0, pending: 0 })),
    open: vi.fn(async () => ({ snapshot, window: snapshot.window })),
    navigate: vi.fn(async () => snapshot),
    layout: vi.fn(async () => snapshot),
    close: vi.fn(async () => snapshot),
    events: vi.fn(async () => []),
    state: vi.fn(async () => snapshot),
    subscribe: vi.fn(async () => () => undefined),
  };
  return {
    workspace: {} as JaWorkbenchAdapters["workspace"],
    review: {} as JaWorkbenchAdapters["review"],
    terminal: {} as JaWorkbenchAdapters["terminal"],
    preview,
  };
}

describe("useJaWorkbench composition", () => {
  it("只转发 shell selection 且不保存第二份 selectedTab", () => {
    const adapters = createAdapters();
    const onSelectedTabChange = vi.fn();
    const previewSessionHints: PreviewSessionHintStorage = {
      read: () => undefined,
      remember: () => undefined,
      forget: () => undefined,
    };
    const { result } = renderHook(() =>
      useJaWorkbench(project, adapters, previewSessionHints, onSelectedTabChange),
    );

    const previewTab = capabilityWorkbenchTab("preview");
    act(() => result.current.onTabChange(previewTab));

    expect(result.current).not.toHaveProperty("selectedTab");
    expect(onSelectedTabChange).toHaveBeenCalledWith(previewTab);
    expect(adapters.preview.open).not.toHaveBeenCalled();
  });
});
