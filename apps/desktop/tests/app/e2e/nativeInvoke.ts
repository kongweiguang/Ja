// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export interface NativeInvokeRequest {
  command: string;
  args: Record<string, unknown>;
}

type NativeInvokeDelegate<T> = () => Promise<T>;
type NativeInvokeProbe = <T>(
  request: NativeInvokeRequest,
  delegate: NativeInvokeDelegate<T>,
) => Promise<T>;

interface NativeInvokeProbeGlobal {
  __JA_E2E_NATIVE_INVOKE_PROBE__?: NativeInvokeProbe;
}

/**
 * E2E bundle 在 Tauri adapter 的窄边界观察命令与故障注入；该实现只由 E2E Vite plugin
 * 解析，生产 dev/build 永远使用 src 中无探针的直接委托实现。
 */
export async function invokeNativeCommand<T>(
  command: string,
  args: Record<string, unknown> | undefined,
  delegate: NativeInvokeDelegate<T>,
): Promise<T> {
  const probe = (globalThis as typeof globalThis & NativeInvokeProbeGlobal)
    .__JA_E2E_NATIVE_INVOKE_PROBE__;
  return probe === undefined ? delegate() : probe({ command, args: args ?? {} }, delegate);
}
