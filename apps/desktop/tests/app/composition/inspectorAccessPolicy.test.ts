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
  /** 直接入口与延迟 Markdown intent 分开验收；intent 必须经当前 Thread Host 回到组合根的统一开栏动作。 @author kongweiguang */
  it("only opens the inspector from approved object actions and explicit Composer commands", () => {
    const source = applicationSource();
    const workbenchHostSource = readFileSync(
      join(process.cwd(), "apps", "desktop", "src", "app", "composition", "WorkbenchHost.tsx"),
      "utf8",
    );
    expect(explicitInspectorOpeners(source)).toEqual([
      "openWorkspaceReferencePreview",
      "openAttachmentPreview",
      "openTurnReview",
      "openTaskFromTimeline",
      "showWorkbenchCapability",
    ]);
    expect(source).toMatch(
      /const requestConversationOpenTarget = useCallback\([\s\S]*?setOpenTargetRequest\(\{\s*requestId,\s*workspaceId,\s*threadId,/,
    );
    expect(source).toMatch(
      /openTargetRequest=\{openTargetRequest\}[\s\S]*?onOpenTargetSettled=\{settleConversationOpenTarget\}/,
    );
    expect(source).toContain("onShowCapability={showWorkbenchCapability}");
    expect(workbenchHostSource).toMatch(
      /const activateWorkbenchCapability = useCallback\([\s\S]*?onShowCapability\(tab\);\s*return;/,
    );
    expect(workbenchHostSource).toMatch(
      /const executeOpenTargetRequest = useCallback\([\s\S]*?if \(request\.target\.kind === "url"\) \{\s*activateWorkbenchCapability\("preview"\)/,
    );
    expect(workbenchHostSource).toMatch(
      /if \(\s*resolution\.withinWorkspace &&\s*!resolution\.readOnly &&\s*resolution\.workspaceRelativePath !== null\s*\) \{\s*activateWorkbenchCapability\("files"\)/,
    );
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
    expect(source).toContain("workspace.workspace !== undefined && !settingsVisible");
    expect(source).toContain("<WorkbenchResizeHandle");
    expect(source).toContain("hidden={!workbenchVisible}");
    expect(source).toContain("aria-hidden={!workbenchVisible || undefined}");
  });
});
