// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import { assertNoReadyTokenLeak, ParamsSchemaByMethod, ResultSchemaByMethod } from "./protocol";

/** 参数与结果 Schema 的共同键就是唯一客户端方法闭集，避免维护第二份类型专用目录。 */
export type ClientMethod = keyof typeof ParamsSchemaByMethod & keyof typeof ResultSchemaByMethod;
export type MethodParams<M extends ClientMethod> = z.infer<(typeof ParamsSchemaByMethod)[M]>;
export type MethodResult<M extends ClientMethod> = z.infer<(typeof ResultSchemaByMethod)[M]>;

/** 以客户端方法名公开严格参数目录，调用方不需要接触内部元组结构。 */
/** 以客户端方法名公开严格结果目录，保证响应校验与请求方法一一对应。 */

/** 请求占用 pending 槽位或进入传输层前先校验参数，非法输入不得消耗并发预算。 */
export function parseMethodParams<M extends ClientMethod>(
  method: M,
  params: unknown,
): MethodParams<M> {
  const allowCredentialSecret = method === "credential/set";
  assertNoReadyTokenLeak(params, { allowCredentialSecret });
  return ParamsSchemaByMethod[method].parse(params) as MethodParams<M>;
}

/** 按原始方法校验结果，防止不同方法的投影被错误关联。 */
export function parseMethodResult<M extends ClientMethod>(
  method: M,
  result: unknown,
): MethodResult<M> {
  assertNoReadyTokenLeak(result);
  return ResultSchemaByMethod[method].parse(result) as MethodResult<M>;
}
