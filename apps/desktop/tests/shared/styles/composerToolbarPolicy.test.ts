// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const COMPOSER_SOURCE = readFileSync(
  join(
    process.cwd(),
    "apps",
    "desktop",
    "src",
    "features",
    "conversation",
    "ui",
    "composer",
    "composer.css",
  ),
  "utf8",
);
const GOALS_SOURCE = readFileSync(
  join(process.cwd(), "apps", "desktop", "src", "features", "goals", "ui", "goals.css"),
  "utf8",
);

describe("Composer toolbar layout policy", () => {
  it("keeps access disclosure compact and preserves the Plan or Goal label", () => {
    expect(COMPOSER_SOURCE).toMatch(
      /\.ja-composer__access-select\s*\{[^}]*width:\s*fit-content;[^}]*flex:\s*0 0 auto;[^}]*gap:\s*4px;/u,
    );
    expect(GOALS_SOURCE).toMatch(/\.ja-composer-goal-status\s*\{[^}]*flex:\s*0 0 auto;/u);
  });

  /** 文件树引用必须锁定到与普通附件相同的横向文件卡尺寸，避免 flex 把文件名压成零宽。 */
  it("keeps workspace file references readable instead of collapsing into icon squares", () => {
    expect(COMPOSER_SOURCE).toMatch(
      /\.ja-composer-context-list:not\(\.is-compact\)[^{]*\[data-reference-type="workspace"\]\s*\{[^}]*box-sizing:\s*border-box;[^}]*width:\s*196px;[^}]*height:\s*72px;[^}]*flex:\s*0 0 auto;[^}]*grid-template-columns:\s*42px minmax\(0, 1fr\);/u,
    );
    expect(COMPOSER_SOURCE).not.toMatch(/\.ja-composer-context(?=[:.{\s])/u);
  });
});
