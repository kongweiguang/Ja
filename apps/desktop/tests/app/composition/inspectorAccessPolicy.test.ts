// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** 静态约束壳层唯一开闭入口，防止后续快捷键或 Command 再次绕过抽屉按钮。 */
function applicationSource(): string {
  return readFileSync(
    join(process.cwd(), "apps", "desktop", "src", "app", "composition", "JaApplication.tsx"),
    "utf8",
  );
}

describe("inspector access policy", () => {
  it("does not open the inspector from workbench shortcuts or the command palette", () => {
    const source = applicationSource();
    expect(source).not.toContain("setInspectorOpen(true)");
    expect(source).not.toContain('id: "toggle-workbench"');
    expect(source).not.toContain("onFocusConversation={returnToConversation}");
    expect(source).toContain("!workspaceShortcutCapabilitiesEnabled || !inspectorOpen");
    expect(source).toContain('workspace.workspace?.kind === "general"');
    expect(source).toContain("<WorkbenchResizeHandle");
    expect(source).toContain("hidden={!workbenchVisible}");
    expect(source).toContain("aria-hidden={!workbenchVisible || undefined}");
  });
});
