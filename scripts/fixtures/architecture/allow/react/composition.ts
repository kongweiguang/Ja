// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { HistoryAdapter } from "@/api/tauri/history";
import { observeWindowCloseRequested } from "@/api/tauri/window";

/** composition 只接收 typed adapter 与 observer，不读取 wire DTO。 */
export const compositionFixture = { observeWindowCloseRequested } satisfies {
  observeWindowCloseRequested: typeof observeWindowCloseRequested;
};

export type CompositionHistoryAdapter = HistoryAdapter;
