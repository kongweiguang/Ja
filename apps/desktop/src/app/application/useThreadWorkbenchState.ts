// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useState, type Dispatch, type SetStateAction } from "react";

/**
 * 只保存进程期右栏投影，Thread 身份仍由 App Server 提供。setter 捕获发起会话，
 * 因而隐藏工作面的异步完成只能更新原会话，不能覆盖后来选中的会话。
 */
export function useThreadWorkbenchState<T>(
  scope: string,
  initial: T,
): readonly [T, Dispatch<SetStateAction<T>>] {
  const [values, setValues] = useState<ReadonlyMap<string, T>>(() => new Map());
  const value = values.has(scope) ? (values.get(scope) as T) : initial;
  /** 按发起 scope 原子归约，不从当前选中会话读取旧值。 */
  const update = useCallback<Dispatch<SetStateAction<T>>>(
    (action) => {
      setValues((current) => {
        const previous = current.has(scope) ? (current.get(scope) as T) : initial;
        const next = typeof action === "function" ? (action as (value: T) => T)(previous) : action;
        if (Object.is(previous, next)) return current;
        return new Map(current).set(scope, next);
      });
    },
    [initial, scope],
  );
  return [value, update];
}
