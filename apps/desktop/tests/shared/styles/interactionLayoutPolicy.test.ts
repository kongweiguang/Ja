// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** 读取交互卡片样式，契约测试只锁定布局边界，不把浏览器像素值伪装成单元测试事实。 */
function styleSource(...segments: string[]): string {
  return readFileSync(
    join(process.cwd(), "apps", "desktop", "src", "features", "conversation", "ui", ...segments),
    "utf8",
  );
}

/** 读取应用壳样式，确认 dock 的剩余高度策略位于唯一的壳层 owner。 */
function appStyleSource(): string {
  return readFileSync(join(process.cwd(), "apps", "desktop", "src", "app", "App.css"), "utf8");
}

const INTERACTION_STYLE = styleSource("interaction", "interaction.css");
const COMPOSER_STYLE = styleSource("composer", "composer.css");
const APP_STYLE = appStyleSource();

describe("conversation interaction layout policy", () => {
  /** 卡片必须给正文留下可收缩轨道，短窗口时由正文滚动而不是挤压操作栏。 */
  it("keeps the interaction card body in a shrinkable scrolling track", () => {
    expect(INTERACTION_STYLE).toMatch(
      /\.ja-interaction-card\[data-interaction-status="pending"\]\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;/su,
    );
    expect(INTERACTION_STYLE).toMatch(/\.ja-interaction-card\s*\{[^}]*min-height:\s*0;/su);
    expect(INTERACTION_STYLE).toMatch(
      /\.ja-interaction-card__body\s*\{[^}]*min-height:\s*0;[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*auto;/su,
    );
  });

  /** dock 必须约束两兄弟的总高度，并让 Composer 回到正常流避免 sticky 覆盖卡片。 */
  it("reserves dock space for the Composer without sticky overlap", () => {
    expect(APP_STYLE).toMatch(
      /\.ja-conversation-composer-dock\s*\{[^}]*display:\s*flex;[^}]*min-height:\s*0;[^}]*max-height:\s*100%;/su,
    );
    expect(APP_STYLE).toMatch(
      /\.ja-conversation-composer-dock\s*>\s*\.ja-interaction-card\s*\{[^}]*min-height:\s*0;[^}]*flex:\s*1\s+1\s+auto;/su,
    );
    expect(APP_STYLE).toMatch(
      /\.ja-conversation-composer-dock\s*>\s*\.ja-interaction-card\.is-collapsed\s*\{[^}]*flex:\s*0\s+0\s+auto;/su,
    );
    expect(INTERACTION_STYLE).toMatch(
      /\.ja-conversation-composer-dock\s*>\s*\.ja-interaction-card\s+\.ja-interaction-card__body\s*\{[^}]*max-height:\s*none;/su,
    );
    expect(COMPOSER_STYLE).toMatch(
      /\.ja-conversation-composer-dock\s*>\s*\.ja-composer\s*\{[^}]*position:\s*relative;[^}]*bottom:\s*auto;[^}]*flex:\s*0\s+0\s+auto;/su,
    );
  });
});
