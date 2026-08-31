// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 稳定 Tab 标识只表达 UI 意图，文件、终端和 Preview 状态仍由各 feature 持有。 */
export type WorkbenchCapabilityTab = "review" | "files" | "terminal" | "preview" | "new";

/** Workbench Tab 与真实能力一一对应，不接受旧 id 或跨 feature 别名。 */
export type WorkbenchTab = WorkbenchCapabilityTab;
