// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { describe, expect, it } from "vitest";
import { applyTheme, resolveTheme } from "@/shared/styles/theme";

describe("theme selection", () => {
  it("resolves explicit and system themes predictably", () => {
    expect(resolveTheme("light", true)).toBe("light");
    expect(resolveTheme("dark", false)).toBe("dark");
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
  });

  it("writes semantic document attributes and stable state classes", () => {
    const root = document.createElement("html");
    applyTheme(root, {
      mode: "dark",
      palette: "xcode",
      highContrast: true,
      reduceMotion: true,
      reducedTransparency: true,
      prefersDark: false,
    });
    expect(root.dataset["theme"]).toBe("dark");
    expect(root.dataset["themeMode"]).toBe("dark");
    expect(root.dataset["palette"]).toBe("xcode");
    expect(root.dataset["highContrast"]).toBe("true");
    expect(root.dataset["reduceMotion"]).toBe("true");
    expect(root.dataset["reducedTransparency"]).toBe("true");
    expect(root).toHaveClass(
      "ja-theme-dark",
      "ja-theme-mode-dark",
      "ja-palette-xcode",
      "ja-high-contrast",
      "ja-reduce-motion",
      "ja-reduced-transparency",
    );
  });

  it("resolves a system theme and retires legacy palette values", () => {
    const root = document.createElement("html");
    root.className = "ja-theme-dark ja-high-contrast";
    applyTheme(root, {
      mode: "system",
      palette: "developer_blue",
      highContrast: false,
      reduceMotion: false,
      reducedTransparency: false,
      prefersDark: false,
    });
    expect(root.dataset["theme"]).toBe("light");
    expect(root.dataset["themeMode"]).toBe("system");
    expect(root.dataset["palette"]).toBe("ja");
    expect(root).toHaveClass("ja-theme-light", "ja-theme-mode-system", "ja-palette-ja");
    expect(root).not.toHaveClass("ja-theme-dark", "ja-high-contrast", "ja-reduce-motion");
    expect(root).not.toHaveClass("ja-reduced-transparency");
  });

  it.each([
    ["jetbrains", "light"],
    ["jetbrains", "dark"],
    ["xcode", "light"],
    ["xcode", "dark"],
    ["ja", "light"],
    ["ja", "dark"],
    ["obsidian", "light"],
    ["obsidian", "dark"],
    ["claude", "light"],
    ["claude", "dark"],
  ] as const)(
    "applies the %s palette in %s mode without leaving the previous palette class",
    (palette, mode) => {
      const root = document.createElement("html");
      root.className =
        "ja-palette-xcode ja-palette-ja ja-palette-jetbrains ja-palette-obsidian ja-palette-claude";
      applyTheme(root, {
        mode,
        palette,
        highContrast: false,
        reduceMotion: false,
        reducedTransparency: false,
        prefersDark: false,
      });
      expect(root.dataset["palette"]).toBe(palette);
      expect(root).toHaveClass(`ja-palette-${palette}`, `ja-theme-${mode}`);
      expect(
        ["xcode", "ja", "jetbrains", "obsidian", "claude"].filter((value) =>
          root.classList.contains(`ja-palette-${value}`),
        ),
      ).toEqual([palette]);
    },
  );
});
