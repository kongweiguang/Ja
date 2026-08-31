// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { detectDesktopPlatform, type NavigatorLike } from "@/features/navigation";

describe("detectDesktopPlatform", () => {
  it.each([
    [{ platform: "MacIntel", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)" }, "macos"],
    [{ platform: "Win32", userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" }, "windows"],
    [{ platform: "Linux x86_64", userAgent: "Mozilla/5.0 (X11; Linux x86_64)" }, "linux"],
  ] satisfies Array<[NavigatorLike, "macos" | "windows" | "linux"]>)(
    "detects %s as %s",
    (input, expected) => {
      expect(detectDesktopPlatform(input)).toBe(expected);
    },
  );

  it("uses userAgentData when the legacy platform field is absent", () => {
    expect(detectDesktopPlatform({ userAgentData: { platform: "macOS" } })).toBe("macos");
  });

  it("does not mistake Android's Linux token for a desktop host", () => {
    expect(
      detectDesktopPlatform({
        platform: "Linux armv8l",
        userAgent: "Mozilla/5.0 (Linux; Android 14)",
      }),
    ).toBe("unknown");
  });

  it("falls back to unknown for browser-like or missing metadata", () => {
    expect(detectDesktopPlatform({ userAgent: "Mozilla/5.0" })).toBe("unknown");
    expect(detectDesktopPlatform({})).toBe("unknown");
  });
});
