// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { createE2eProjectPicker, isAbsoluteProjectPath } from "./projectPicker";

describe("E2E project picker", () => {
  it("returns the exact drive, UNC, or Unix absolute path", async () => {
    const paths = ["C:\\dev\\Ja Project", "\\\\server\\share\\ja", "/tmp/ja-project"];

    for (const path of paths) {
      await expect(createE2eProjectPicker({ VITE_JA_E2E_PROJECT_PATH: path }).pick()).resolves.toBe(
        path,
      );
    }
  });

  it("fails closed for a missing or malformed path", () => {
    const invalidPaths = [
      undefined,
      "",
      " ",
      "relative/path",
      "C:relative",
      " C:\\dev\\ja",
      "C:\\dev\\ja ",
      "C:\\dev\\ja\nother",
      "C:\\dev\\ja\0other",
      "\\\\server",
    ];

    for (const path of invalidPaths) {
      if (path !== undefined) expect(isAbsoluteProjectPath(path)).toBe(false);
      expect(() => createE2eProjectPicker({ VITE_JA_E2E_PROJECT_PATH: path })).toThrow(
        "Ja E2E project path is missing or invalid",
      );
    }
  });
});
