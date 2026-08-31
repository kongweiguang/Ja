// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { resolveCommandActions } from "@/features/command/application/commandActions";

describe("Command application actions", () => {
  it("treats throwing availability predicates as unavailable", () => {
    const actions = resolveCommandActions([
      {
        id: "broken",
        label: "异常",
        keywords: [],
        availability: () => {
          throw new Error("bad state");
        },
        invoke: vi.fn(),
      },
      { id: "ready", label: "可用", keywords: [], availability: true, invoke: vi.fn() },
    ]);

    expect(actions.map((action) => [action.id, action.available])).toEqual([
      ["broken", false],
      ["ready", true],
    ]);
  });
});
