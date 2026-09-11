// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  JA_CLOSE_BEHAVIOR_COMMANDS,
  readCloseBehavior,
  saveCloseBehavior,
} from "@/api/tauri/closeBehavior";

const native = vi.hoisted(() => ({ invoke: vi.fn() }));

vi.mock("@tauri-apps/api/core", () => ({ invoke: native.invoke }));

describe("close behavior adapter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    native.invoke.mockResolvedValue(undefined);
  });

  it("reads and validates the native lifecycle preference", async () => {
    native.invoke.mockResolvedValueOnce("exit");

    await expect(readCloseBehavior()).resolves.toBe("exit");
    expect(native.invoke).toHaveBeenCalledWith(JA_CLOSE_BEHAVIOR_COMMANDS.read, {});

    native.invoke.mockResolvedValueOnce("unexpected");
    await expect(readCloseBehavior()).rejects.toThrow("response invalid");
  });

  it("saves only the closed set of values through the typed command", async () => {
    await saveCloseBehavior("background");

    expect(native.invoke).toHaveBeenCalledWith(JA_CLOSE_BEHAVIOR_COMMANDS.save, {
      value: "background",
    });
    await expect(saveCloseBehavior("invalid" as never)).rejects.toThrow("invalid close behavior");
    expect(native.invoke).toHaveBeenCalledOnce();
  });
});
