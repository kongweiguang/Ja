// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, it } from "vitest";

/** 侧边外壳不能覆盖 Composer 内部焦点与文本框，视觉状态由主组件唯一维护。 */
it("侧边任务沿用主 Composer 焦点边框而不增加内部框", () => {
  const css = readFileSync(resolve("apps/desktop/src/features/tasks/ui/tasks.css"), "utf8");
  expect(css).not.toMatch(/\.ja-task-composer\s+(?:textarea|input)/u);
  expect(css).not.toContain(".ja-task-composer-status");
});
