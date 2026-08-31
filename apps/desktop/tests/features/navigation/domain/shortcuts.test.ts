// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  matchNavigationShortcut,
  navigationShortcut,
  shouldPreserveEditableShortcut,
} from "@/features/navigation";

/** 构造快捷键匹配器所需的最小不可变键盘事件形状，避免测试依赖浏览器实现细节。 */
function keyboard(key: string, overrides: Partial<KeyboardEvent> = {}): KeyboardEvent {
  return {
    altKey: false,
    ctrlKey: false,
    defaultPrevented: false,
    isComposing: false,
    key,
    metaKey: false,
    repeat: false,
    shiftKey: false,
    getModifierState: () => false,
    ...overrides,
  } as KeyboardEvent;
}

describe("navigation shortcuts", () => {
  it("uses Command on macOS and Ctrl on Windows", () => {
    expect(navigationShortcut("toggle-sidebar", "macos")).toEqual({
      display: "⌘B",
      aria: "Meta+B",
    });
    expect(navigationShortcut("toggle-sidebar", "windows")).toEqual({
      display: "Ctrl+B",
      aria: "Control+B",
    });
    expect(matchNavigationShortcut(keyboard("b", { metaKey: true }), "macos", false)).toBe(
      "toggle-sidebar",
    );
    expect(matchNavigationShortcut(keyboard("b", { ctrlKey: true }), "windows", false)).toBe(
      "toggle-sidebar",
    );
    expect(
      matchNavigationShortcut(keyboard("b", { ctrlKey: true }), "macos", false),
    ).toBeUndefined();
  });

  it("separates command palette, settings, and history navigation without retaining the removed workspace-search shortcut", () => {
    expect(matchNavigationShortcut(keyboard("k", { ctrlKey: true }), "windows", false)).toBe(
      "search-conversations",
    );
    expect(
      matchNavigationShortcut(keyboard("f", { ctrlKey: true, shiftKey: true }), "windows", false),
    ).toBeUndefined();
    expect(matchNavigationShortcut(keyboard(",", { metaKey: true }), "macos", false)).toBe(
      "open-settings",
    );
    expect(matchNavigationShortcut(keyboard("ArrowLeft", { altKey: true }), "windows", false)).toBe(
      "go-back",
    );
    expect(matchNavigationShortcut(keyboard("]", { metaKey: true }), "macos", false)).toBe(
      "go-forward",
    );
  });

  it("routes the visible right-side capability shortcuts to real workbench commands", () => {
    expect(navigationShortcut("open-review", "windows").display).toBe("Ctrl+Shift+G");
    expect(navigationShortcut("open-terminal", "windows").display).toBe("Ctrl+`");
    expect(navigationShortcut("open-preview", "macos").display).toBe("⌘T");
    expect(navigationShortcut("focus-conversation", "windows").display).toBe("Ctrl+Alt+S");
    expect(
      matchNavigationShortcut(keyboard("g", { ctrlKey: true, shiftKey: true }), "windows", false),
    ).toBe("open-review");
    expect(matchNavigationShortcut(keyboard("p", { metaKey: true }), "macos", false)).toBe(
      "open-files",
    );
    expect(matchNavigationShortcut(keyboard("`", { ctrlKey: true }), "windows", false)).toBe(
      "open-terminal",
    );
    expect(
      matchNavigationShortcut(keyboard("s", { ctrlKey: true, altKey: true }), "windows", false),
    ).toBe("focus-conversation");
  });

  it("keeps all five Workbench commands global for an xterm-like textarea", () => {
    const terminalInput = document.createElement("textarea");
    terminalInput.className = "xterm-helper-textarea";
    document.body.append(terminalInput);
    const shortcuts = [
      keyboard("g", { ctrlKey: true, shiftKey: true, target: terminalInput }),
      keyboard("p", { ctrlKey: true, target: terminalInput }),
      keyboard("`", { ctrlKey: true, target: terminalInput }),
      keyboard("t", { ctrlKey: true, target: terminalInput }),
      keyboard("s", { ctrlKey: true, altKey: true, target: terminalInput }),
    ];

    for (const event of shortcuts) {
      const command = matchNavigationShortcut(event, "windows", false);
      expect(command).toBeDefined();
      expect(shouldPreserveEditableShortcut(command!, event)).toBe(false);
    }
    expect(
      shouldPreserveEditableShortcut(
        "toggle-sidebar",
        keyboard("b", { ctrlKey: true, target: terminalInput }),
      ),
    ).toBe(true);
    terminalInput.remove();
  });

  it("ignores composition, repeat, mixed modifiers, and unowned keys", () => {
    expect(
      matchNavigationShortcut(
        keyboard("n", { ctrlKey: true, isComposing: true }),
        "windows",
        false,
      ),
    ).toBeUndefined();
    expect(
      matchNavigationShortcut(keyboard("n", { ctrlKey: true, repeat: true }), "windows", false),
    ).toBeUndefined();
    expect(
      matchNavigationShortcut(keyboard("n", { ctrlKey: true, altKey: true }), "windows", false),
    ).toBeUndefined();
    expect(
      matchNavigationShortcut(keyboard("x", { ctrlKey: true }), "windows", false),
    ).toBeUndefined();
  });

  it("fails closed for AltGraph before Ctrl+Alt can be mistaken for Side Chat", () => {
    const altGraph = keyboard("s", { ctrlKey: true, altKey: true });

    expect(matchNavigationShortcut(altGraph, "windows", true)).toBeUndefined();
    expect(matchNavigationShortcut(altGraph, "windows", false)).toBe("focus-conversation");
  });
});
