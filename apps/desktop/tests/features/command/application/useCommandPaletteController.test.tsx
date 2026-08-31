// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useCommandPaletteController } from "@/features/command/application/useCommandPaletteController";

describe("Command Palette controller", () => {
  it("filters in application and guards one command against synchronous duplicate execution", async () => {
    let finish: (() => void) | undefined;
    const invoke = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const onOpenChange = vi.fn();
    const { result } = renderHook(() =>
      useCommandPaletteController({
        commands: [
          {
            id: "slow",
            label: "慢动作",
            keywords: ["approval_required"],
            availability: true,
            invoke,
          },
          {
            id: "other",
            label: "其它动作",
            keywords: ["other"],
            availability: true,
            invoke: vi.fn(),
          },
        ],
        onOpenChange,
      }),
    );

    act(() => result.current.actions.changeQuery("approval_required"));
    expect(result.current.viewModel.commands.map((command) => command.id)).toEqual(["slow"]);

    act(() => {
      result.current.actions.executeCommand("slow");
      result.current.actions.executeCommand("slow");
    });
    await waitFor(() => expect(invoke).toHaveBeenCalledOnce());
    expect(result.current.viewModel.busy).toBe(true);
    expect(onOpenChange).toHaveBeenCalledWith(false);

    act(() => finish?.());
    await waitFor(() => expect(result.current.viewModel.busy).toBe(false));
  });
});
