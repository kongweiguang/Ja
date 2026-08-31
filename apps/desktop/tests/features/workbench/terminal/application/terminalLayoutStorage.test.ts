// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import fc from "fast-check";
import { describe, expect, it } from "vitest";
import {
  MAX_TERMINAL_LAYOUT_TABS,
  createDefaultTerminalLayout,
  parseTerminalLayout,
} from "@/features/workbench/terminal/domain/terminalLayout";
import { LocalTerminalLayoutStorage } from "@/features/workbench/terminal/application/terminalLayoutStorage";

// 固定 seed 让 CI 失败能用同一命令稳定回放；fast-check 仍会输出最小反例 path。
const TERMINAL_LAYOUT_PROPERTY_SEED = 0x4a415001;

/** 以内存 Map 实现最小介质端口，使 application 测试不依赖浏览器 storage 所有权。 */
function createMemoryMedia(): {
  readonly values: Map<string, string>;
  readonly storage: {
    getItem(key: string): string | null;
    setItem(key: string, value: string): void;
  };
} {
  const values = new Map<string, string>();
  return {
    values,
    storage: {
      getItem(key: string): string | null {
        return values.get(key) ?? null;
      },
      setItem(key: string, value: string): void {
        values.set(key, value);
      },
    },
  };
}

describe("TerminalLayoutStorage", () => {
  it("loads and saves only the current strict terminal schema", () => {
    const media = createMemoryMedia();
    const storage = new LocalTerminalLayoutStorage(() => media.storage);
    const layout = createDefaultTerminalLayout("ws_fixture");
    storage.save(layout);
    expect(storage.load("ws_fixture")).toEqual(layout);
    const raw = media.values.get("ja-terminal-layouts-v1") ?? "";
    expect(raw).toContain('"version":1');
    expect(raw).not.toContain("sessionId");
  });

  it("does not rewrite rejected media while reading", () => {
    const raw = JSON.stringify({
      version: 1,
      layouts: {
        ws_fixture: {
          ...createDefaultTerminalLayout("ws_fixture"),
          sessionId: "must-not-migrate",
        },
      },
    });
    const media = createMemoryMedia();
    media.values.set("ja-terminal-layouts-v1", raw);
    const storage = new LocalTerminalLayoutStorage(() => media.storage);
    expect(storage.load("ws_fixture")).toBeUndefined();
    expect(media.values.get("ja-terminal-layouts-v1")).toBe(raw);
  });

  it("rejects arbitrary JSON without throwing or accepting unknown top-level fields", () => {
    fc.assert(
      fc.property(fc.jsonValue(), (value) => {
        expect(() => parseTerminalLayout(value, "ws_property")).not.toThrow();
        const parsed = parseTerminalLayout(value, "ws_property");
        if (parsed !== undefined) {
          expect(parsed.workspaceId).toBe("ws_property");
          expect(Object.keys(parsed).sort()).toEqual([
            "activeTabId",
            "tabs",
            "version",
            "workspaceId",
          ]);
        }
      }),
      { seed: TERMINAL_LAYOUT_PROPERTY_SEED, numRuns: 300, endOnFailure: true },
    );
  });

  it("rejects every non-empty unknown field name on an otherwise valid layout", () => {
    fc.assert(
      fc.property(
        fc
          .string({ minLength: 1 })
          .filter((key) => !["activeTabId", "tabs", "version", "workspaceId"].includes(key)),
        fc.jsonValue(),
        (key, value) => {
          const candidate = { ...createDefaultTerminalLayout("ws_property"), [key]: value };
          expect(parseTerminalLayout(candidate, "ws_property")).toBeUndefined();
        },
      ),
      { seed: TERMINAL_LAYOUT_PROPERTY_SEED + 1, numRuns: 200, endOnFailure: true },
    );
  });

  it("rejects unknown fields nested in tabs or pane nodes", () => {
    fc.assert(
      fc.property(
        fc.constantFrom("tab", "pane"),
        fc
          .string({ minLength: 1 })
          .filter(
            (key) =>
              ![
                "activePaneId",
                "first",
                "kind",
                "orientation",
                "paneId",
                "profile",
                "ratio",
                "relativeCwd",
                "root",
                "second",
                "splitId",
                "tabId",
                "title",
              ].includes(key),
          ),
        fc.jsonValue(),
        (target, key, value) => {
          const layout = createDefaultTerminalLayout("ws_property");
          const tab = layout.tabs[0]!;
          const candidate = {
            ...layout,
            tabs: [
              target === "tab"
                ? { ...tab, [key]: value }
                : { ...tab, root: { ...tab.root, [key]: value } },
            ],
          };
          expect(parseTerminalLayout(candidate, "ws_property")).toBeUndefined();
        },
      ),
      { seed: TERMINAL_LAYOUT_PROPERTY_SEED + 2, numRuns: 250, endOnFailure: true },
    );
  });

  it("fails closed for generated over-budget and illegal layout shapes", () => {
    fc.assert(
      fc.property(
        fc.constantFrom(
          "over-budget-tabs",
          "wrong-version",
          "wrong-workspace",
          "non-array-tabs",
          "unknown-profile",
          "invalid-active-tab",
        ),
        fc.integer({ min: 1, max: 32 }),
        fc.string(),
        (mutation, extra, generated) => {
          const layout = createDefaultTerminalLayout("ws_property");
          const tab = layout.tabs[0]!;
          const candidate: unknown =
            mutation === "over-budget-tabs"
              ? {
                  ...layout,
                  tabs: Array.from({ length: MAX_TERMINAL_LAYOUT_TABS + extra }, (_, index) => ({
                    ...tab,
                    tabId: `tab-${index}`,
                    activePaneId: `pane-${index}`,
                    root: { kind: "pane", paneId: `pane-${index}` },
                  })),
                }
              : mutation === "wrong-version"
                ? { ...layout, version: layout.version + extra }
                : mutation === "wrong-workspace"
                  ? { ...layout, workspaceId: `${generated}_other` }
                  : mutation === "non-array-tabs"
                    ? { ...layout, tabs: { generated } }
                    : mutation === "unknown-profile"
                      ? {
                          ...layout,
                          tabs: [{ ...tab, profile: `${generated}__unsupported` }],
                        }
                      : { ...layout, activeTabId: extra };

          expect(parseTerminalLayout(candidate, "ws_property")).toBeUndefined();
        },
      ),
      { seed: TERMINAL_LAYOUT_PROPERTY_SEED + 3, numRuns: 300, endOnFailure: true },
    );
  });
});
