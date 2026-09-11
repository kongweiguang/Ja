// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const TOKENS_SOURCE = readFileSync(
  join(process.cwd(), "apps", "desktop", "src", "shared", "styles", "tokens.css"),
  "utf8",
).toLowerCase();
const INTERACTION_SOURCE = readFileSync(
  join(
    process.cwd(),
    "apps",
    "desktop",
    "src",
    "features",
    "conversation",
    "ui",
    "interaction",
    "interaction.css",
  ),
  "utf8",
).toLowerCase();
const GOALS_SOURCE = readFileSync(
  join(process.cwd(), "apps", "desktop", "src", "features", "goals", "ui", "goals.css"),
  "utf8",
).toLowerCase();

/** 解析六位十六进制颜色，供 token 对比度回归使用。 */
function parseHex(value: string): readonly [number, number, number] {
  expect(value).toMatch(/^#[0-9a-f]{6}$/u);
  const channels: [number, number, number] = [
    Number.parseInt(value.slice(1, 3), 16),
    Number.parseInt(value.slice(3, 5), 16),
    Number.parseInt(value.slice(5, 7), 16),
  ];
  return channels;
}

/** 按 WCAG sRGB 定义计算相对亮度，避免把“看起来更深”当成对比度证据。 */
function relativeLuminance(value: string): number {
  const channels = parseHex(value).map((channel) => {
    const normalized = channel / 255;
    return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0]! + 0.7152 * channels[1]! + 0.0722 * channels[2]!;
}

/** 对比度顺序不影响结果；主按钮和小字共用同一客观阈值。 */
function contrastRatio(first: string, second: string): number {
  const firstLuminance = relativeLuminance(first);
  const secondLuminance = relativeLuminance(second);
  return (
    (Math.max(firstLuminance, secondLuminance) + 0.05) /
    (Math.min(firstLuminance, secondLuminance) + 0.05)
  );
}

/** 模拟 CSS color-mix(in srgb, emphasis 80%, foreground 20%) 的确定性结果。 */
function mixSrgb(emphasis: string, foreground: string): string {
  const emphasisChannels = parseHex(emphasis);
  const foregroundChannels = parseHex(foreground);
  return `#${emphasisChannels
    .map((channel, index) =>
      Math.round(channel * 0.8 + foregroundChannels[index]! * 0.2)
        .toString(16)
        .padStart(2, "0"),
    )
    .join("")}`;
}

describe("Plan and interaction accent contrast policy", () => {
  it("routes small accent text and primary action surfaces through emphasis", () => {
    expect(INTERACTION_SOURCE).toMatch(
      /\.ja-interaction-card__eyebrow\s*\{[^}]*color:\s*color-mix\(in srgb,\s*var\(--ja-accent-emphasis\)\s+80%,\s*var\(--ja-foreground\)\);/u,
    );
    expect(INTERACTION_SOURCE).toMatch(
      /\.ja-interaction-card__recommended\s*\{[^}]*color:\s*color-mix\(in srgb,\s*var\(--ja-accent-emphasis\)\s+80%,\s*var\(--ja-foreground\)\);/u,
    );
    expect(INTERACTION_SOURCE).toMatch(
      /\.ja-interaction-card__primary-button\s*\{[^}]*background:\s*var\(--ja-accent-emphasis\);/u,
    );
    expect(INTERACTION_SOURCE).toMatch(
      /\.ja-interaction-card__option\[data-selected="true"\]\s*\{[^}]*border-color:\s*var\(--ja-accent\);[^}]*background:\s*color-mix\(in srgb,\s*var\(--ja-accent\)/u,
    );
    expect(GOALS_SOURCE).toMatch(
      /\.ja-plan-timeline__footer\s*>\s*button\s*\{[^}]*color:\s*color-mix\(in srgb,\s*var\(--ja-accent-emphasis\)\s+80%,\s*var\(--ja-foreground\)\);/u,
    );
    expect(GOALS_SOURCE).toMatch(
      /\.ja-plan-timeline__actions\s+button\s*\{[^}]*color:\s*color-mix\(in srgb,\s*var\(--ja-accent-emphasis\)\s+80%,\s*var\(--ja-foreground\)\);/u,
    );
    expect(GOALS_SOURCE).toMatch(
      /\.ja-plan-timeline__actions\s+button\.is-primary\s*\{[^}]*background:\s*var\(--ja-accent-emphasis\);/u,
    );
    expect(GOALS_SOURCE).toMatch(
      /\.ja-plan-alert\s+button\s*\{[^}]*color:\s*color-mix\(in srgb,\s*var\(--ja-accent-emphasis\)\s+80%,\s*var\(--ja-foreground\)\);/u,
    );
    expect(GOALS_SOURCE).toMatch(
      /\.ja-plan-button\.is-primary\s*\{[^}]*background:\s*var\(--ja-accent-emphasis\);/u,
    );
  });

  it("keeps the default light and dark primary roles above 4.5 to 1", () => {
    expect(contrastRatio("#625ce9", "#ffffff"), "light emphasis with white").toBeGreaterThanOrEqual(
      4.5,
    );
    expect(contrastRatio("#ffffff", "#625ce9"), "light primary button").toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio("#0b0b0d", "#726cf9"), "dark primary button").toBeGreaterThanOrEqual(4.5);
  });

  it("keeps mixed secondary accent text readable on light and dark card surfaces", () => {
    const lightText = mixSrgb("#625ce9", "#1b1b1f");
    const darkText = mixSrgb("#726cf9", "#f2f2f3");
    expect(
      contrastRatio(lightText, "#ffffff"),
      "light card secondary accent",
    ).toBeGreaterThanOrEqual(4.5);
    expect(
      contrastRatio(darkText, "#202124"),
      "dark interaction card secondary accent",
    ).toBeGreaterThanOrEqual(4.5);
    expect(
      contrastRatio(darkText, "#18191b"),
      "dark Plan surface secondary accent",
    ).toBeGreaterThanOrEqual(4.5);
  });

  it("retains the high-contrast and forced-colors emphasis fallback", () => {
    expect(TOKENS_SOURCE).toContain(':root[data-high-contrast="true"]');
    expect(TOKENS_SOURCE).toContain("@media (forced-colors: active)");
    expect(TOKENS_SOURCE).toMatch(/--ja-accent-emphasis:\s*highlight;/u);
  });
});
