// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

type NativeInvokeDelegate<T> = () => Promise<T>;

/**
 * 把固定 command 的原生调用收敛到统一异步边界；这里刻意不读取全局变量或构建环境，
 * 保证生产 adapter 的行为不会因测试运行方式变化。E2E 观察由独立测试入口替换本模块。
 */
export async function invokeNativeCommand<T>(
  _command: string,
  _args: Record<string, unknown> | undefined,
  delegate: NativeInvokeDelegate<T>,
): Promise<T> {
  return delegate();
}
