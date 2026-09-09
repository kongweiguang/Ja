// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** 读取组合根源码，让壳层 inspector ownership 约束独立于 React 渲染细节。 */
function applicationSource(): string {
  return readFileSync(
    join(process.cwd(), "apps", "desktop", "src", "app", "composition", "JaApplication.tsx"),
    "utf8",
  );
}

/**
 * 将每个显式打开动作归属到最近的 useCallback；这里不解析完整 AST，是因为 Gate 只守护
 * JaApplication 的组合层入口，函数名白名单比调用次数魔数更能暴露新增 ownership。
 */
function explicitInspectorOpeners(source: string): string[] {
  return [...source.matchAll(/setInspectorOpen\(true\)/g)].map((call) => {
    const declarations = [
      ...source
        .slice(0, call.index)
        .matchAll(/\bconst\s+([A-Za-z][A-Za-z0-9]*)\s*=\s*useCallback\b/g),
    ];
    return declarations.at(-1)?.[1] ?? "<module>";
  });
}

describe("inspector access policy", () => {
  /** 对象入口覆盖文件引用与指定 Turn；白名单与真实组合根保持一致，不恢复 latest-only 入口。 */
  it("only opens the inspector from approved object actions and explicit Composer commands", () => {
    const source = applicationSource();
    expect(explicitInspectorOpeners(source)).toEqual([
      "openWorkspaceReferencePreview",
      "openAttachmentPreview",
      "openTurnReview",
      "openTaskFromTimeline",
      "showWorkbenchCapability",
    ]);
    expect(source).toMatch(
      /const openAttachmentPreview = useCallback\([\s\S]*?setWorkbenchTab\("preview"\);[\s\S]*?setInspectorOpen\(true\);/,
    );
    expect(source).toMatch(
      /const openTurnReview = useCallback\([\s\S]*?setWorkbenchTab\("review"\);[\s\S]*?setInspectorOpen\(true\);/,
    );
    expect(source).toMatch(
      /const openTaskFromTimeline = useCallback\([\s\S]*?setWorkbenchTab\(tab\.key\);[\s\S]*?setInspectorOpen\(true\);/,
    );
    expect(source).toMatch(
      /const showWorkbenchCapability = useCallback\([\s\S]*?setWorkbenchTab\(tab\);[\s\S]*?setInspectorOpen\(true\);/,
    );
    expect(source).not.toContain('id: "toggle-workbench"');
    expect(source).not.toContain("onFocusConversation={returnToConversation}");
    expect(source).toContain("!workspaceShortcutCapabilitiesEnabled || !inspectorOpen");
    expect(source).toContain('workspace.workspace?.kind === "general"');
    expect(source).toContain("<WorkbenchResizeHandle");
    expect(source).toContain("hidden={!workbenchVisible}");
    expect(source).toContain("aria-hidden={!workbenchVisible || undefined}");
  });
});
