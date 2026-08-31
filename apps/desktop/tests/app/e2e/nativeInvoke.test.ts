// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import { invokeNativeCommand, type NativeInvokeRequest } from "./nativeInvoke";

interface NativeInvokeProbeGlobal {
  __JA_E2E_NATIVE_INVOKE_PROBE__?: <T>(
    request: NativeInvokeRequest,
    delegate: () => Promise<T>,
  ) => Promise<T>;
}

type NativeInvokeProbe = NonNullable<NativeInvokeProbeGlobal["__JA_E2E_NATIVE_INVOKE_PROBE__"]>;

const probeGlobal = globalThis as typeof globalThis & NativeInvokeProbeGlobal;

afterEach(() => {
  delete probeGlobal.__JA_E2E_NATIVE_INVOKE_PROBE__;
});

describe("E2E native invoke bridge", () => {
  it("delegates directly when the page probe is absent", async () => {
    const delegate = vi.fn(async () => "native-result");
    await expect(invokeNativeCommand("ja_fixture", { value: 1 }, delegate)).resolves.toBe(
      "native-result",
    );
    expect(delegate).toHaveBeenCalledOnce();
  });

  it("routes through the page probe without changing the command envelope", async () => {
    const delegate = vi.fn(async () => "native-result");
    let probeCalls = 0;
    const probe: NativeInvokeProbe = async <T>(
      request: NativeInvokeRequest,
      run: () => Promise<T>,
    ): Promise<T> => {
      probeCalls += 1;
      return { request, result: await run() } as T;
    };
    probeGlobal.__JA_E2E_NATIVE_INVOKE_PROBE__ = probe;

    await expect(invokeNativeCommand("ja_fixture", { value: 2 }, delegate)).resolves.toEqual({
      request: { command: "ja_fixture", args: { value: 2 } },
      result: "native-result",
    });
    expect(probeCalls).toBe(1);
    expect(delegate).toHaveBeenCalledOnce();
  });
});
